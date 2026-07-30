package com.evacuation.engine.dto.dispatch.response;

/**
 * The JSON shape of {@link com.evacuation.engine.dispatch.ImprovementLoop.Result} — what one LNS
 * pass ran and the plan's resulting score. Callers re-fetch {@code GET /api/dispatch/plan/current}
 * afterward to see the routes themselves; this response is the pass's own report, not the plan.
 *
 * @param iterationsRun         how many improvement attempts actually ran
 * @param accepted              how many of those improved the plan and were kept
 * @param totalPeoplePlaced     the plan's total placed people after this pass
 * @param weightedPersonMinutes the plan's weighted person-minutes after this pass
 * @param makespanBucket        the plan's makespan after this pass
 * @param minSlackBuckets       the plan's minimum horizon slack after this pass
 */
public record ImproveResponse(int iterationsRun, int accepted, int totalPeoplePlaced,
                              double weightedPersonMinutes, int makespanBucket,
                              double minSlackBuckets) {
}
