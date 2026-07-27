package com.evacuation.engine.graph.time;

import com.evacuation.engine.graph.spatial.GeoUtils;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.model.entity.BlockedRoad;
import com.evacuation.engine.model.entity.HazardEvent;
import com.evacuation.engine.model.entity.RoadEdge;
import com.evacuation.engine.model.enums.RoadStatus;
import com.evacuation.engine.repository.graph.BlockedRoadRepository;
import com.evacuation.engine.repository.graph.HazardEventRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles a fresh {@link HazardTimeline} from the current {@link GraphSnapshot}, the live
 * {@code BlockedRoad} overlay rows, and the active {@code HazardEvent} predictions.
 *
 * <p>The static passes are the zero-behavior-change migration seam: they must produce exactly the
 * traversal decisions the old boolean overlay produced, only expressed as time-bucketed hazard states.
 * Every legacy block becomes {@link HazardState#LETHAL} across the entire compiled horizon, and a
 * PARTIALLY_BLOCKED edge becomes {@link HazardState#RISKY} (passable but priced) — the direct analogue
 * of the old penalty.
 *
 * <p>Bucket 0 is this compile's epoch — "the moment of this compile". All hazards are anchored to it;
 * because legacy blocks carry no time dimension they simply fill {@code [0, horizon)} uniformly, while
 * a hazard event places its intervals at whatever future bucket its front actually arrives.
 *
 * <p>Base topology state ({@code RoadEdge.roadStatus}, {@code RoadNode.active}) is not re-queried here
 * — the snapshot already captured both at build time via {@code edgeBaseStatus(slot)}/
 * {@code nodeActive(nodeIndex)} — so the only live reads are the dynamic {@code BlockedRoad} overlay
 * and the {@code HazardEvent} table.
 *
 * <p><strong>Expanding-circle onset buckets.</strong> A {@code HazardEvent} is a front spreading from
 * an origin: it already covers {@code r0} metres ({@code initialRadiusMeters}) when it starts and
 * advances at {@code g} metres per minute ({@code growthRateMetersPerMinute} — Scenario 2's flood
 * front runs at roughly 200 m/min). For a cell at great-circle distance {@code d} from the origin, the
 * front needs {@code (d - r0) / g} minutes after {@code eventStartTime} to arrive; subtracting the
 * minutes already elapsed since that start converts it into minutes from <em>now</em>, and
 * {@code TimeModel} rounds that into the cell's <strong>onset bucket</strong> — the first bucket at
 * which the expanding radius has reached it. The elapsed term is signed, so a forecast whose
 * {@code eventStartTime} is still in the future correctly pushes every onset bucket further out rather
 * than pulling it in. A cell whose onset falls past the horizon is simply not written: the event never
 * reaches it inside the compiled plan.
 *
 * <p>{@code leadingRiskBufferMeters} gives the front a transition band instead of a cliff. The same
 * arithmetic run against {@code d - r0 - buffer} yields an earlier RISKY onset, so a cell goes
 * SAFE → RISKY (carrying the event's {@code rho}, its {@code riskFactor}) → LETHAL as the front closes
 * in — which is what makes {@code h(x, b)} genuinely three-state rather than a boolean with extra
 * steps.
 *
 * <p><strong>Precedence is upgrade-only.</strong> Every pass writes through
 * {@link #upgradeEdgeCell} / {@link #upgradeNodeCell}, which raise a cell's severity and never lower
 * it: LETHAL beats RISKY beats SAFE, and between two RISKY assertions the higher rho wins. Passes are
 * therefore order-independent and compose as an AND over "everything asserted about this cell", the
 * safety-conservative reading already used for BlockedRoad versus base status. It also means a cell
 * cannot be un-blocked by a later pass simply because that pass had nothing to say about it.
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

    private static final double METERS_PER_KM = 1000.0;
    private static final double SECONDS_PER_MINUTE = 60.0;

    private final BlockedRoadRepository blockedRoadRepository;
    private final HazardEventRepository hazardEventRepository;
    private final TimeModel timeModel;

    /** A front's two onset buckets for one cell: when it turns RISKY, and when it turns LETHAL. */
    private record Onset(int riskyBucket, int lethalBucket) {
    }

    /**
     * Compiles the hazard timeline for a snapshot at the current instant.
     *
     * @param snapshot the immutable graph the timeline is sized to and compiled against
     * @return a fresh, immutable hazard timeline paired with {@code snapshot}'s graph version
     */
    public HazardTimeline compile(GraphSnapshot snapshot) {
        // 1. One clock read for the whole compile: this instant is bucket 0 for every pass below,
        //    and the same instant the timeline records as its build time.
        LocalDateTime compiledAt = LocalDateTime.now();

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
                upgradeEdgeCell(edgeState, edgeRisk, slot, 0, horizon, horizon,
                        HazardState.LETHAL, 0.0f);
            } else if (baseStatus == RoadStatus.PARTIALLY_BLOCKED) {
                upgradeEdgeCell(edgeState, edgeRisk, slot, 0, horizon, horizon,
                        HazardState.RISKY, PARTIALLY_BLOCKED_RISK_FACTOR);
            }
        }

        // 3. Reverse index edgeDbId -> slots (a bidirectional edge shares one id across two slots).
        Map<Long, List<Integer>> edgeDbIdToSlots = new HashMap<>();
        for (int slot = 0; slot < edgeSlotCount; slot++) {
            edgeDbIdToSlots.computeIfAbsent(snapshot.edgeDbId(slot), key -> new ArrayList<>()).add(slot);
        }

        // 4. Active BlockedRoad overlay — an active row fully blocks, and LETHAL always wins,
        //    overriding any PARTIALLY_BLOCKED risk state (the upgrade clears its risk too).
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
                upgradeEdgeCell(edgeState, edgeRisk, slot, 0, horizon, horizon,
                        HazardState.LETHAL, 0.0f);
            }
        }

        // 5. Base node status, captured in the snapshot.
        for (int nodeIndex = 0; nodeIndex < nodeCount; nodeIndex++) {
            if (!snapshot.nodeActive(nodeIndex)) {
                upgradeNodeCell(nodeState, nodeIndex, 0, horizon, horizon, HazardState.LETHAL);
            }
        }

        // 6. Summary counts for the static passes, taken here — while every written cell is still
        //    uniform across [0, horizon) — since the hazard pass below deliberately is not.
        int lethalEdges = countUniformState(edgeState, edgeSlotCount, horizon, LETHAL);
        int riskyEdges = countUniformState(edgeState, edgeSlotCount, horizon, RISKY);
        int lethalNodes = countUniformState(nodeState, nodeCount, horizon, LETHAL);

        // 7. Active HazardEvent predictions — expanding circles written as future-dated onsets.
        List<HazardEvent> hazardEvents = hazardEventRepository.findByActive(true);
        int hazardEdgeCellsUpgraded = 0;
        int hazardNodeCellsUpgraded = 0;

        for (HazardEvent event : hazardEvents) {
            double originLat = event.getOriginLatitude();
            double originLon = event.getOriginLongitude();
            double r0 = event.getInitialRadiusMeters();
            double g = event.getGrowthRateMetersPerMinute();
            double buffer = event.getLeadingRiskBufferMeters();
            float rho = event.getRiskFactor().floatValue();

            // Positive once the front has been advancing; negative while the event is still a forecast,
            // which delays every onset bucket rather than advancing it.
            double signedElapsedMinutes =
                    Duration.between(event.getEventStartTime(), compiledAt).toSeconds() / SECONDS_PER_MINUTE;

            // Edge slots, walked exactly as the CSR is: each slot's representative point is the
            // midpoint of its two endpoints — plenty at ward scale, and consistent with the
            // "a linear scan is fine at this size" choices made elsewhere (NearestNodeLocator).
            for (int nodeIndex = 0; nodeIndex < nodeCount; nodeIndex++) {
                double fromLat = snapshot.nodeLat(nodeIndex);
                double fromLon = snapshot.nodeLon(nodeIndex);
                int slotsEnd = snapshot.slotsEnd(nodeIndex);

                for (int slot = snapshot.slotsStart(nodeIndex); slot < slotsEnd; slot++) {
                    int toNodeIndex = snapshot.edgeTo(slot);
                    double midLat = (fromLat + snapshot.nodeLat(toNodeIndex)) / 2.0;
                    double midLon = (fromLon + snapshot.nodeLon(toNodeIndex)) / 2.0;
                    double distanceMeters =
                            GeoUtils.haversineKm(originLat, originLon, midLat, midLon) * METERS_PER_KM;

                    Onset onset = onsetFor(distanceMeters, r0, g, buffer, signedElapsedMinutes);

                    if (onset.lethalBucket() < horizon) {
                        hazardEdgeCellsUpgraded += upgradeEdgeCell(edgeState, edgeRisk, slot,
                                onset.lethalBucket(), horizon, horizon, HazardState.LETHAL, 0.0f);
                    }
                    if (onset.riskyBucket() < onset.lethalBucket() && onset.riskyBucket() < horizon) {
                        hazardEdgeCellsUpgraded += upgradeEdgeCell(edgeState, edgeRisk, slot,
                                onset.riskyBucket(), Math.min(onset.lethalBucket(), horizon), horizon,
                                HazardState.RISKY, rho);
                    }
                }
            }

            // Nodes, at their own coordinates.
            for (int nodeIndex = 0; nodeIndex < nodeCount; nodeIndex++) {
                double distanceMeters = GeoUtils.haversineKm(originLat, originLon,
                        snapshot.nodeLat(nodeIndex), snapshot.nodeLon(nodeIndex)) * METERS_PER_KM;

                Onset onset = onsetFor(distanceMeters, r0, g, buffer, signedElapsedMinutes);

                if (onset.lethalBucket() < horizon) {
                    hazardNodeCellsUpgraded += upgradeNodeCell(nodeState, nodeIndex,
                            onset.lethalBucket(), horizon, horizon, HazardState.LETHAL);
                }
                if (onset.riskyBucket() < onset.lethalBucket() && onset.riskyBucket() < horizon) {
                    hazardNodeCellsUpgraded += upgradeNodeCell(nodeState, nodeIndex,
                            onset.riskyBucket(), Math.min(onset.lethalBucket(), horizon), horizon,
                            HazardState.RISKY);
                }
            }
        }

        // 8. Build the timeline.
        HazardTimeline timeline = new HazardTimeline(
                horizon, edgeState, edgeRisk, nodeState,
                snapshot.graphVersion(), System.currentTimeMillis(), compiledAt);

        log.info("Compiled hazard timeline v{} (graph v{}, horizon {} buckets): "
                        + "base status and active blocks gave {} edge slots LETHAL, {} RISKY, {} nodes LETHAL; "
                        + "{} active hazard events raised {} edge-slot cells and {} node cells",
                timeline.timelineVersion(), snapshot.graphVersion(), horizon,
                lethalEdges, riskyEdges, lethalNodes,
                hazardEvents.size(), hazardEdgeCellsUpgraded, hazardNodeCellsUpgraded);

        return timeline;
    }

    /**
     * Computes when an expanding front reaches one cell, in buckets from now.
     *
     * <p>{@code d} is the cell's distance from the origin in metres, {@code r0} the front's initial
     * radius, {@code g} its growth rate in metres per minute, and {@code buffer} the width of the
     * RISKY band running ahead of it. Both onsets are floored at zero — a cell already inside the
     * front is affected from bucket 0, not retroactively.
     *
     * @param distanceMeters       the cell's great-circle distance from the event origin, in metres
     * @param r0                   the front's initial radius, in metres
     * @param g                    the front's growth rate, in metres per minute
     * @param buffer               the leading RISKY band's width, in metres
     * @param signedElapsedMinutes minutes since the event started; negative if it has not started
     * @return the cell's RISKY and LETHAL onset buckets, relative to this compile's epoch
     */
    private Onset onsetFor(double distanceMeters, double r0, double g, double buffer,
                           double signedElapsedMinutes) {
        double totalMinutesToLethal = Math.max(0.0, (distanceMeters - r0) / g);
        double minutesFromNowToLethal = Math.max(0.0, totalMinutesToLethal - signedElapsedMinutes);
        int lethalOnsetBucket = timeModel.bucketsForSeconds(minutesFromNowToLethal * SECONDS_PER_MINUTE);

        double totalMinutesToRisky = Math.max(0.0, (distanceMeters - r0 - buffer) / g);
        double minutesFromNowToRisky = Math.max(0.0, totalMinutesToRisky - signedElapsedMinutes);
        int riskyOnsetBucket = timeModel.bucketsForSeconds(minutesFromNowToRisky * SECONDS_PER_MINUTE);

        return new Onset(riskyOnsetBucket, lethalOnsetBucket);
    }

    /**
     * Raises an edge slot's hazard state across {@code [fromBucket, toBucketExclusive)}, clamped to
     * the horizon.
     *
     * <p>Severity only ever goes up: a bucket is rewritten when {@code newState} outranks what is
     * already there (LETHAL over RISKY over SAFE), in which case {@code newRisk} replaces the cell's
     * risk too — which is how a LETHAL upgrade clears a RISKY cell's leftover rho. A second RISKY
     * assertion over an already-RISKY bucket keeps the higher of the two rho values and leaves the
     * state alone.
     *
     * @return the number of buckets whose <em>state</em> was actually raised (a rho-only increase is
     *         not counted), so a caller can report its own pass's contribution
     */
    private int upgradeEdgeCell(byte[] edgeState, float[] edgeRisk, int slot,
                                int fromBucket, int toBucketExclusive, int horizon,
                                HazardState newState, float newRisk) {
        int from = Math.max(0, fromBucket);
        int to = Math.min(toBucketExclusive, horizon);
        byte newOrdinal = (byte) newState.ordinal();
        int base = slot * horizon;
        int upgraded = 0;

        for (int bucket = from; bucket < to; bucket++) {
            int cell = base + bucket;
            byte current = edgeState[cell];

            if (newOrdinal > current) {
                edgeState[cell] = newOrdinal;
                edgeRisk[cell] = newRisk;
                upgraded++;
            } else if (newOrdinal == current
                    && newState == HazardState.RISKY
                    && newRisk > edgeRisk[cell]) {
                edgeRisk[cell] = newRisk;
            }
        }
        return upgraded;
    }

    /**
     * Raises a node's hazard state across {@code [fromBucket, toBucketExclusive)}, clamped to the
     * horizon, under the same upgrade-only rule as {@link #upgradeEdgeCell}. Nodes carry no risk
     * factor, so there is no rho to compare.
     *
     * @return the number of buckets whose state was actually raised
     */
    private int upgradeNodeCell(byte[] nodeState, int nodeIndex,
                                int fromBucket, int toBucketExclusive, int horizon,
                                HazardState newState) {
        int from = Math.max(0, fromBucket);
        int to = Math.min(toBucketExclusive, horizon);
        byte newOrdinal = (byte) newState.ordinal();
        int base = nodeIndex * horizon;
        int upgraded = 0;

        for (int bucket = from; bucket < to; bucket++) {
            int cell = base + bucket;
            if (newOrdinal > nodeState[cell]) {
                nodeState[cell] = newOrdinal;
                upgraded++;
            }
        }
        return upgraded;
    }

    /**
     * Counts cells in the given state, reading only bucket 0 of each slot/node — valid only while
     * every written cell is still uniform across the horizon, i.e. before the hazard-event pass runs.
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