package com.evacuation.engine.algorithm.spacetime;

/**
 * What a {@link TimeExpandedDijkstra} search is trying to reach — carried as values only, never as
 * a predicate, so a chosen destination can be stored on a committed {@code DispatchResult} and
 * replayed verbatim by a later repair or LNS pass without reconstructing the original search intent.
 *
 * <p>{@link AnyEligibleShelter} is today's behaviour: the multi-target virtual super-sink over
 * whichever shelters the caller's live {@code Predicate<GraphSnapshot.ShelterRef>} accepts at call
 * time — that predicate cannot live here because it depends on occupancy that changes between calls.
 * {@link FixedNode} is a single dense node index chosen by whoever is being routed; nothing about it
 * changes between the original search and a later repair, so it belongs on the value itself.
 */
public sealed interface Destination {

    /** Route to any shelter the caller's live eligibility predicate accepts. */
    record AnyEligibleShelter() implements Destination {
    }

    /**
     * Route to one specific node.
     *
     * @param targetNodeIndex dense index of the destination node
     */
    record FixedNode(int targetNodeIndex) implements Destination {
        public FixedNode {
            if (targetNodeIndex < 0) {
                throw new IllegalArgumentException(
                        "targetNodeIndex must be >= 0, got " + targetNodeIndex);
            }
        }
    }

    /** Shared instance for the common case — every caller wanting "nearest shelter" can reuse it. */
    Destination ANY_SHELTER = new AnyEligibleShelter();

    static Destination anyShelter() {
        return ANY_SHELTER;
    }
}
