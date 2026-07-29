package com.evacuation.engine.dispatch;

import com.evacuation.engine.algorithm.spacetime.TimedWalk;

import java.util.Comparator;
import java.util.List;

/**
 * The design's §3.5 objective, made executable: the single yardstick that answers "is this plan
 * better than that one?" for two candidate dispatch outcomes.
 *
 * <p>This is what {@code ImprovementLoop}'s anytime local search consults to decide whether a
 * tentative destroy-and-repair move is kept or rolled back, and it is the same yardstick the Phase 5
 * A/B comparison uses to rank entirely different strategies against each other — independent
 * Dijkstra, STRIDE-greedy, STRIDE + LNS. Because both uses reduce to "score two lists and compare",
 * this class is pure and stateless: it never reads the {@link ReservationLedger}, never touches the
 * {@link PlanBook}, and holds no Spring-managed state. A candidate and an incumbent are just two
 * {@code List<DispatchResult>}, scored independently and compared.
 *
 * <p><strong>The four levels, most important first.</strong> Each is consulted only when every level
 * before it is <em>exactly</em> tied:
 *
 * <ol>
 *   <li><strong>Total people placed</strong> — maximized. Feasibility dominates everything: a plan
 *       that routes one more person wins outright, no matter how much worse it is on every other
 *       measure.</li>
 *   <li><strong>Weighted person-minutes</strong> — minimized. See below for exactly what is and is
 *       not folded into this number.</li>
 *   <li><strong>Makespan</strong> — minimized. The latest arrival bucket anywhere in the plan: when
 *       the last platoon reaches shelter, i.e. network clearance time.</li>
 *   <li><strong>Minimum slack</strong> — maximized, as a proxy. See below for why it is a proxy and
 *       why that is acceptable here.</li>
 * </ol>
 *
 * <p><strong>Level 2 does not re-add the exposure term, and that is deliberate.</strong> The doc's
 * literal formula reads {@code Σ w_i · Σ p_ij · (arrival − r_i) · Δ + λ · exposure}, so a reader who
 * knows only that formula would expect a second, separately-computed exposure term here. There is
 * none, because there does not need to be: {@link TimedWalk#totalCost()} is the accumulated cost the
 * space-time search itself produced, and {@code TimeExpandedDijkstra} prices {@code λ · exposure}
 * into the cost of every RISKY cell <em>at the moment it relaxes that arc</em>. Risk is priced in
 * during the search, not bolted on afterwards — exactly as §1 says it should be — so summing
 * {@code totalCost()} already carries it. Re-adding an exposure term at this level would double-count
 * the very same person-seconds.
 *
 * <p><strong>What "weighted" means here.</strong> Each result's cost is weighted by its platoon
 * {@link DispatchResult#size()} — cost is per-platoon, the objective is per-person, and the size is
 * the bridge between them. The doc's per-party priority weight {@code w_i} is not multiplied in at
 * this level; priority is expressed instead in the dispatch <em>ordering</em>
 * ({@code DispatchService.orderForDispatch}), where a critical party picks its route before a low
 * one. That is a simplification of the literal formula, recorded here rather than silently dropped.
 * Likewise, the cost summed is the search's own accumulated cost — travel, waiting, and priced
 * exposure — rather than the literal {@code (arrival − r_i)} span from request to arrival.
 *
 * <p><strong>Level 4 is a proxy, stated plainly.</strong> The doc defines minimum temporal slack as
 * the smallest gap anywhere in the plan between a platoon's exit from an arc and that arc's predicted
 * hazard onset — a per-arc scan against the {@link com.evacuation.engine.graph.time.HazardTimeline}.
 * What is computed here instead is horizon slack: for each committed result, how many buckets remain
 * between its arrival and the compiled horizon, minimized across the plan. It measures how much room
 * the worst-off platoon has left before it runs off the edge of what was even computed, which
 * correlates with, but is not, the literal hazard margin. The tradeoff is taken knowingly, on two
 * grounds. First, this level only ever fires as a tie-breaker — it is reached only when two plans
 * place identically many people, cost identically many person-minutes, and finish in the same bucket
 * — so it is a nudge between near-identical plans, not a driver of them. Second, the hard safety
 * property it might otherwise be asked to guarantee is already guaranteed elsewhere and by
 * construction: constraint C3's {@code β} margin is enforced during expansion, so no plan this class
 * can ever be handed contains an arc with insufficient hazard margin. §4 already declares max-min
 * slack approximated rather than exact; this is that approximation, in code.
 *
 * <p><strong>Exact double comparison is used throughout</strong>, matching this project's production
 * convention — {@code TimeExpandedDijkstra}'s own relaxation test compares accumulated costs with a
 * bare {@code <}. Epsilon tolerance belongs to test assertions, never to decision logic: a tolerance
 * here would make "better" non-transitive near the threshold and could hand the improvement loop a
 * comparator that violates its own contract.
 */
public final class LexicographicObjective {

    private static final double SECONDS_PER_MINUTE = 60.0;

    /**
     * The whole objective, as one chained comparator, built once.
     *
     * <p>Chained in the same style as {@code DispatchService.orderForDispatch}, for consistency with
     * the idiom already established in this package. {@code .reversed()} is applied to precisely the
     * two levels that are <em>maximized</em> — total people placed and minimum slack — because a
     * comparator's natural order is ascending, and ascending means "worse first" for a quantity where
     * more is better. The two minimized levels keep their natural order untouched.
     *
     * <p>Note that {@code .reversed()} here is applied to each individual level's comparator before
     * it joins the chain, never to the chain itself: reversing the chain would invert every level at
     * once, which is a different (and wrong) ordering.
     */
    private static final Comparator<Score> LEXICOGRAPHIC_ORDER = Comparator
            .comparingInt((Score score) -> score.totalPeoplePlaced()).reversed()
            .thenComparing(Comparator.comparingDouble(
                    (Score score) -> score.weightedPersonMinutes()))
            .thenComparing(Comparator.comparingInt(
                    (Score score) -> score.makespanBucket()))
            .thenComparing(Comparator.comparingDouble(
                    (Score score) -> score.minSlackBuckets()).reversed());

    private LexicographicObjective() {
        // Static-only helper; never instantiated.
    }

    /**
     * One plan's four objective levels, computed once so a comparison never re-walks the plan.
     *
     * <p>No compact constructor and no validation: every component is a computed output of
     * {@link #score(List, int)}, never a value a caller supplies by hand, so there is no external
     * input here to defend against.
     *
     * @param totalPeoplePlaced     sum of every committed platoon's size — maximized
     * @param weightedPersonMinutes summed {@code totalCost() × size}, in minutes — minimized
     * @param makespanBucket        the latest arrival bucket in the plan — minimized
     * @param minSlackBuckets       the worst-off platoon's remaining buckets to the horizon —
     *                              maximized
     */
    public record Score(int totalPeoplePlaced, double weightedPersonMinutes, int makespanBucket,
                        double minSlackBuckets) {
    }

    /**
     * Scores one candidate plan across all four levels in a single pass over its committed results.
     *
     * <p>An empty plan scores vacuously on the two levels that need a baseline: makespan is
     * {@code 0} (nothing to finish, so nothing finishes late) and minimum slack is
     * {@code horizonBuckets} itself (nothing committed, so nothing is at risk of running out of
     * horizon). Both are the correct limits rather than sentinel values, which is what lets an empty
     * plan be compared against a non-empty one without a special case at the comparison site — level
     * 1 will have already decided that comparison in the non-empty plan's favour anyway.
     *
     * @param committed      the plan's committed results; only these count — shortfalls are the
     *                       complement of level 1 and need no separate term
     * @param horizonBuckets the compiled planning horizon {@code T}, in buckets, that these results
     *                       were searched against
     * @return the plan's score
     * @throws IllegalArgumentException if {@code horizonBuckets <= 0} — there is no such thing as a
     *                                  zero-length compiled horizon, so this is a caller bug rather
     *                                  than a plan that merely scores badly
     */
    public static Score score(List<DispatchResult> committed, int horizonBuckets) {
        if (horizonBuckets <= 0) {
            throw new IllegalArgumentException(
                    "horizonBuckets must be > 0, got " + horizonBuckets);
        }

        int totalPeoplePlaced = 0;
        double weightedPersonSeconds = 0.0;
        int makespanBucket = 0;

        // Seeded at the horizon, which is simultaneously the empty-plan answer and a true upper
        // bound for any real result: an arrival bucket is never negative, so no committed platoon
        // can have more remaining room than the horizon itself.
        double minSlackBuckets = horizonBuckets;

        for (DispatchResult result : committed) {
            TimedWalk walk = result.searchResult().walk();
            int arrivalBucket = walk.arrival().bucket();

            totalPeoplePlaced += result.size();
            weightedPersonSeconds += walk.totalCost() * result.size();
            makespanBucket = Math.max(makespanBucket, arrivalBucket);
            minSlackBuckets = Math.min(minSlackBuckets, horizonBuckets - arrivalBucket);
        }

        return new Score(totalPeoplePlaced, weightedPersonSeconds / SECONDS_PER_MINUTE,
                makespanBucket, minSlackBuckets);
    }

    /**
     * Compares two scores in {@link Comparator} convention: negative if {@code a} is the better plan,
     * positive if {@code b} is, zero only when all four levels tie exactly.
     *
     * <p>Delegates to {@link #LEXICOGRAPHIC_ORDER}, so the ordering exists in exactly one place and
     * cannot drift between the comparator a caller sorts with and the comparison this method makes.
     *
     * @param a the first score
     * @param b the second score
     * @return a negative integer, zero, or a positive integer as {@code a} is better than, equal to,
     *         or worse than {@code b}
     */
    public static int compare(Score a, Score b) {
        return LEXICOGRAPHIC_ORDER.compare(a, b);
    }

    /**
     * Whether {@code candidate} is strictly better than {@code incumbent}.
     *
     * <p>Exists purely so that callers never have to remember or re-derive which sign of
     * {@link #compare(Score, Score)} means "better". The improvement loop's accept/rollback test is
     * the single most consequential comparison in the engine — get the sign backwards there and the
     * loop cheerfully walks the plan downhill while reporting monotone improvement, a bug that
     * produces no exception and no obviously wrong output. Naming the intent removes the chance to
     * make that mistake at every call site, which is worth one extra method.
     *
     * @param candidate the tentatively-modified plan's score
     * @param incumbent the currently-accepted plan's score
     * @return {@code true} if the candidate should be accepted, {@code false} if it should be rolled
     *         back — a tie is not an improvement, so the incumbent stands
     */
    public static boolean isBetter(Score candidate, Score incumbent) {
        return compare(candidate, incumbent) < 0;
    }
}