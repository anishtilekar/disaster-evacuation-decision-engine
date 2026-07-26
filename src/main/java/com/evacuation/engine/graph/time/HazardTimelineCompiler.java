package com.evacuation.engine.graph.time;

import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.model.entity.BlockedRoad;
import com.evacuation.engine.model.entity.RoadEdge;
import com.evacuation.engine.model.enums.RoadStatus;
import com.evacuation.engine.repository.graph.BlockedRoadRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles a fresh {@link HazardTimeline} from the current {@link GraphSnapshot} plus live
 * {@code BlockedRoad} overlay rows.
 *
 * <p>This is the zero-behavior-change migration seam: it must produce exactly the traversal decisions
 * the old boolean overlay produced, only expressed as time-bucketed hazard states. Every legacy block
 * becomes {@link HazardState#LETHAL} across the entire compiled horizon, and a PARTIALLY_BLOCKED edge
 * becomes {@link HazardState#RISKY} (passable but priced) — the direct analogue of the old penalty.
 *
 * <p>Bucket 0 is this compile's epoch — "the moment of this compile". All hazards are anchored to it;
 * because legacy blocks carry no time dimension they simply fill {@code [0, horizon)} uniformly.
 * Future phases will place future-dated hazards relative to this same epoch.
 *
 * <p>Base topology state ({@code RoadEdge.roadStatus}, {@code RoadNode.active}) is not re-queried here
 * — the snapshot already captured both at build time via {@code edgeBaseStatus(slot)}/
 * {@code nodeActive(nodeIndex)} — so the only live read is the dynamic {@code BlockedRoad} overlay.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class HazardTimelineCompiler {

    /**
     * Risk factor for RISKY cells derived from {@link RoadStatus#PARTIALLY_BLOCKED} — a placeholder
     * tuning value; a later phase may externalize this into {@code GraphEngineProperties}.
     */
    private static final float PARTIALLY_BLOCKED_RISK_FACTOR = 0.6f;

    private static final byte RISKY = (byte) HazardState.RISKY.ordinal();
    private static final byte LETHAL = (byte) HazardState.LETHAL.ordinal();

    private final BlockedRoadRepository blockedRoadRepository;
    private final TimeModel timeModel;

    /**
     * Compiles the hazard timeline for a snapshot at the current instant.
     *
     * @param snapshot the immutable graph the timeline is sized to and compiled against
     * @return a fresh, immutable hazard timeline paired with {@code snapshot}'s graph version
     */
    public HazardTimeline compile(GraphSnapshot snapshot) {
        int horizon = timeModel.horizonBuckets();
        int edgeSlotCount = snapshot.edgeSlotCount();
        int nodeCount = snapshot.nodeCount();

        // Flat (index * horizon + bucket) layout, matching HazardTimeline. Defaults are 0 ==
        // HazardState.SAFE.ordinal() and 0.0f risk — the correct "nothing wrong" state, no fill needed.
        byte[] edgeState = new byte[edgeSlotCount * horizon];
        float[] edgeRisk = new float[edgeSlotCount * horizon];
        byte[] nodeState = new byte[nodeCount * horizon];

        // 2. Base edge status, captured in the snapshot.
        for (int slot = 0; slot < edgeSlotCount; slot++) {
            RoadStatus baseStatus = snapshot.edgeBaseStatus(slot);
            if (baseStatus == RoadStatus.BLOCKED
                    || baseStatus == RoadStatus.CLOSED
                    || baseStatus == RoadStatus.UNDER_REPAIR) {
                fillState(edgeState, slot, horizon, LETHAL);
            } else if (baseStatus == RoadStatus.PARTIALLY_BLOCKED) {
                fillState(edgeState, slot, horizon, RISKY);
                fillRisk(edgeRisk, slot, horizon, PARTIALLY_BLOCKED_RISK_FACTOR);
            }
        }

        // 3. Reverse index edgeDbId -> slots (a bidirectional edge shares one id across two slots).
        Map<Long, List<Integer>> edgeDbIdToSlots = new HashMap<>();
        for (int slot = 0; slot < edgeSlotCount; slot++) {
            edgeDbIdToSlots.computeIfAbsent(snapshot.edgeDbId(slot), key -> new ArrayList<>()).add(slot);
        }

        // 4. Active BlockedRoad overlay — an active row fully blocks, and LETHAL always wins,
        //    overriding any PARTIALLY_BLOCKED risk state (clear its risk too).
        for (BlockedRoad blockedRoad : blockedRoadRepository.findActiveWithEdge()) {
            RoadEdge edge = blockedRoad.getRoadEdge();
            Long edgeDbId = edge == null ? null : edge.getEdgeId();
            List<Integer> slots = edgeDbId == null ? null : edgeDbIdToSlots.get(edgeDbId);
            if (slots == null) {
                log.warn("Active BlockedRoad references edge id {} not in current snapshot (graph v{}); skipping",
                        edgeDbId, snapshot.graphVersion());
                continue;
            }
            for (int slot : slots) {
                fillState(edgeState, slot, horizon, LETHAL);
                fillRisk(edgeRisk, slot, horizon, 0.0f);
            }
        }

        // 5. Base node status, captured in the snapshot.
        for (int nodeIndex = 0; nodeIndex < nodeCount; nodeIndex++) {
            if (!snapshot.nodeActive(nodeIndex)) {
                fillState(nodeState, nodeIndex, horizon, LETHAL);
            }
        }

        // 6. Build the timeline. Each cell is uniform across [0, horizon) here, so bucket 0 of a
        //    slot/node is representative for the summary counts below.
        HazardTimeline timeline = new HazardTimeline(
                horizon, edgeState, edgeRisk, nodeState,
                snapshot.graphVersion(), System.currentTimeMillis(), LocalDateTime.now());

        int lethalEdges = countUniformState(edgeState, edgeSlotCount, horizon, LETHAL);
        int riskyEdges = countUniformState(edgeState, edgeSlotCount, horizon, RISKY);
        int lethalNodes = countUniformState(nodeState, nodeCount, horizon, LETHAL);
        log.info("Compiled hazard timeline v{} (graph v{}, horizon {} buckets): "
                        + "{} edge slots LETHAL, {} RISKY, {} nodes LETHAL",
                timeline.timelineVersion(), snapshot.graphVersion(), horizon,
                lethalEdges, riskyEdges, lethalNodes);

        return timeline;
    }

    /** Fills a cell's whole {@code [0, horizon)} range in a flat byte state array with one state. */
    private static void fillState(byte[] flat, int index, int horizon, byte state) {
        int base = index * horizon;
        for (int bucket = 0; bucket < horizon; bucket++) {
            flat[base + bucket] = state;
        }
    }

    /** Fills a cell's whole {@code [0, horizon)} range in a flat float risk array with one value. */
    private static void fillRisk(float[] flat, int index, int horizon, float risk) {
        int base = index * horizon;
        for (int bucket = 0; bucket < horizon; bucket++) {
            flat[base + bucket] = risk;
        }
    }

    /**
     * Counts cells in the given state, reading only bucket 0 of each slot/node — valid because this
     * compiler fills every cell uniformly across the horizon.
     */
    private static int countUniformState(byte[] flat, int count, int horizon, byte state) {
        int total = 0;
        for (int i = 0; i < count; i++) {
            if (flat[i * horizon] == state) {
                total++;
            }
        }
        return total;
    }
}