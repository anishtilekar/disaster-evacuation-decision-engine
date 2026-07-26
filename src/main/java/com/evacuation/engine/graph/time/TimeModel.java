package com.evacuation.engine.graph.time;

import com.evacuation.engine.config.GraphEngineProperties;

import org.springframework.stereotype.Component;

/**
 * The single place that converts real durations into STRIDE's discrete time buckets, so no other
 * class does ad-hoc ceil arithmetic.
 *
 * <p>The time axis is discretised into fixed-width buckets of {@link #deltaSeconds()} seconds.
 * Bucket 0 is the plan epoch — "now" at dispatch time — and every bucket index is an offset from it.
 * This class is purely arithmetic: callers pass elapsed durations (a travel time, a number of
 * seconds) and receive bucket counts or vice versa; it holds no clock and no plan state.
 *
 * <p>All methods are pure and deterministic, reading only the configured bucket width and horizon.
 */
@Component
public class TimeModel {

    private static final int SECONDS_PER_MINUTE = 60;

    private final GraphEngineProperties props;

    public TimeModel(GraphEngineProperties props) {
        this.props = props;
    }

    /** @return the bucket width, in seconds — the granularity of the discretised time axis. */
    public int deltaSeconds() {
        return props.getTime().getDeltaSeconds();
    }

    /** @return the rolling planning horizon, in buckets. */
    public int horizonBuckets() {
        return props.getTime().getHorizonBuckets();
    }

    /**
     * @return the hazard safety margin, in buckets: an arc must stay non-lethal for this many buckets
     *         after a platoon exits it.
     */
    public int marginBuckets() {
        return props.getTime().getHazardMarginBuckets();
    }

    /**
     * Converts an arc's travel time into its bucket cost (the {@code tau_a} of the design).
     *
     * <p>Rounded up, and floored at one: an arc always costs at least a single bucket, so no traversal
     * is ever free on the discretised axis.
     *
     * @param travelTimeMinutes the arc's estimated travel time, in minutes
     * @return the arc's cost in buckets, always {@code >= 1}
     */
    public int edgeBuckets(double travelTimeMinutes) {
        double seconds = travelTimeMinutes * SECONDS_PER_MINUTE;
        return Math.max(1, (int) Math.ceil(seconds / deltaSeconds()));
    }

    /**
     * Converts a duration in seconds into whole buckets, rounded up.
     *
     * @param seconds the duration, in seconds
     * @return the number of buckets spanning that duration, floored at {@code 0}
     */
    public int bucketsForSeconds(double seconds) {
        return Math.max(0, (int) Math.ceil(seconds / deltaSeconds()));
    }

    /**
     * Converts a bucket count back into real seconds.
     *
     * @param buckets a number of buckets
     * @return the equivalent duration, in seconds
     */
    public double secondsForBuckets(int buckets) {
        return (double) buckets * deltaSeconds();
    }
}