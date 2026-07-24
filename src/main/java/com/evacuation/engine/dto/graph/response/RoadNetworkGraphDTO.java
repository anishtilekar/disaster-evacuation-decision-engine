package com.evacuation.engine.dto.graph.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoadNetworkGraphDTO {

    private List<RoadNodeResponse> nodes;

    private List<RoadEdgeResponse> edges;

    private List<BlockedRoadResponse> blockedRoads;

    private int totalNodes;

    private int totalEdges;

    private int totalBlockedRoads;

    private LocalDateTime generatedAt;
}