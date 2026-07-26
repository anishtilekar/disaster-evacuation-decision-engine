package com.evacuation.engine.graph.time;

/**
 * One hazard assertion over a contiguous bucket range for a single cell (an arc slot or a node),
 * before compilation into the packed {@code HazardTimeline}.
 *
 * <p>The range is half-open, {@code [fromBucket, toBucket)}: {@code fromBucket} is inclusive and
 * {@code toBucket} is exclusive. {@code riskFactor} is the design's rho, meaningful only when
 * {@code state == RISKY}; pass {@code 0.0} for {@link HazardState#SAFE} and {@link HazardState#LETHAL}.
 *
 * <p>A legacy hard block is just the degenerate case: it compiles to a single
 * {@code LETHAL [nowBucket, horizon)} interval covering the whole remaining plan.
 *
 * @param fromBucket inclusive start bucket ({@code >= 0})
 * @param toBucket   exclusive end bucket ({@code > fromBucket})
 * @param state      the safety classification asserted over the range
 * @param riskFactor risk weight in {@code [0.0, 1.0]}, used only when {@code state == RISKY}
 */
public record HazardInterval(int fromBucket, int toBucket, HazardState state, double riskFactor) {

    public HazardInterval {
        if (fromBucket < 0) {
            throw new IllegalArgumentException("fromBucket must be >= 0, got: " + fromBucket);
        }
        if (toBucket <= fromBucket) {
            throw new IllegalArgumentException(
                    "toBucket must be > fromBucket, got: [" + fromBucket + ", " + toBucket + ")");
        }
        if (riskFactor < 0.0 || riskFactor > 1.0) {
            throw new IllegalArgumentException("riskFactor must be in [0.0, 1.0], got: " + riskFactor);
        }
    }

    /**
     * Whether this interval covers the given bucket, per the half-open convention.
     *
     * @param bucket the bucket index to test
     * @return {@code true} if {@code fromBucket <= bucket < toBucket}
     */
    public boolean contains(int bucket) {
        return fromBucket <= bucket && bucket < toBucket;
    }
}