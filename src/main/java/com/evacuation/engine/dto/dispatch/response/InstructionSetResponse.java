package com.evacuation.engine.dto.dispatch.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The JSON shape of {@link com.evacuation.engine.dispatch.InstructionSet} — every committed
 * platoon's walk, resolved to real coordinates, plus every party that came up short.
 *
 * <p>The internal {@code InstructionSet}/{@code DispatchResult}/{@code TimedWalk} records are never
 * serialized directly: they speak in dense node indices and CSR slots, which is exactly right for
 * the engine and exactly wrong for a frontend, which has no graph structure of its own to resolve
 * them against. This response carries the resolved lat/lon a Leaflet layer can draw with no further
 * lookups.
 *
 * <p>The session fields ({@code sessionActive} through {@code horizonBuckets}) are carried here
 * rather than on a separate endpoint because every bucket number in {@code committed} is meaningless
 * without them — a frontend animating a walk needs to know what bucket 0 means in wall-clock time and
 * how wide a bucket is before it can place a platoon anywhere on a real clock.
 *
 * @param committed      one entry per platoon with a reserved route
 * @param shortfalls     one entry per party that could not be fully placed
 * @param sessionActive  whether a dispatch session is currently live
 * @param sessionEpoch   the instant this session's bucket 0 represents; {@code null} if no session
 *                       is active
 * @param deltaSeconds   the bucket width in seconds
 * @param horizonBuckets the compiled planning horizon in buckets
 */
public record InstructionSetResponse(List<PlatoonPlanResponse> committed,
                                     List<ShortfallResponse> shortfalls,
                                     boolean sessionActive, LocalDateTime sessionEpoch,
                                     int deltaSeconds, int horizonBuckets) {

    /**
     * @param partyId          the shortfall party
     * @param unroutedSize     how many people were never committed to a route
     * @param failedAtWaveIndex the wave that first could not be placed
     */
    public record ShortfallResponse(long partyId, int unroutedSize, int failedAtWaveIndex) {
    }
}
