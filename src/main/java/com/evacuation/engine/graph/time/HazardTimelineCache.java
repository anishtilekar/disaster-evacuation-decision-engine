package com.evacuation.engine.graph.time;

import com.evacuation.engine.graph.structure.GraphSnapshot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the current immutable {@link HazardTimeline} behind an {@link AtomicReference}, mirroring
 * {@code loader/GraphCache}'s copy-on-write discipline exactly.
 *
 * <p>Each timeline is fully immutable, so a routing or dispatch query grabs the current reference once
 * and reads it with no locking; concurrent readers never block each other. A rebuild — triggered by a
 * block event, an unblock, a future hazard-prediction update, or a periodic sweep — compiles an
 * entirely new timeline and publishes it with a single atomic {@code set}, so an in-flight read keeps
 * using the timeline it already holds while new reads pick up the replacement. The swap can neither
 * block nor corrupt a read. This is what lets a hazard event be reflected without ever touching the
 * base {@link GraphSnapshot}: the timeline is recompiled and swapped, the CSR topology stays put.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class HazardTimelineCache {

    private final HazardTimelineCompiler hazardTimelineCompiler;

    private final AtomicReference<HazardTimeline> current = new AtomicReference<>();

    /**
     * Returns the current hazard timeline.
     *
     * @return the current timeline
     * @throws IllegalStateException if none has been compiled yet — i.e. {@link #reload(GraphSnapshot)}
     *                               has not run
     */
    public HazardTimeline get() {
        HazardTimeline timeline = current.get();
        if (timeline == null) {
            throw new IllegalStateException("Hazard timeline not yet compiled — call reload(snapshot) first");
        }
        return timeline;
    }

    /**
     * Recompiles the hazard timeline for the given snapshot and atomically publishes it as current.
     *
     * @param snapshot the immutable graph to compile the timeline against
     * @return the newly compiled timeline
     */
    public HazardTimeline reload(GraphSnapshot snapshot) {
        HazardTimeline timeline = hazardTimelineCompiler.compile(snapshot);
        current.set(timeline);
        log.info("Hazard timeline reloaded: version={}, graph v{}, horizon {} buckets",
                timeline.timelineVersion(), timeline.graphVersion(), timeline.horizonBuckets());
        return timeline;
    }

    /**
     * @return {@code true} if a timeline has been compiled, so callers can check without triggering the
     *         {@link #get()} not-compiled exception
     */
    public boolean isLoaded() {
        return current.get() != null;
    }
}