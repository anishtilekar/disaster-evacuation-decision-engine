package com.evacuation.engine.dto.graph.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.evacuation.engine.model.enums.BlockageReason;
import com.evacuation.engine.model.enums.SeverityLevel;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlockedRoadResponse {

    private Long blockedRoadId;

    private Long disasterId;

    private String disasterName;

    private Long roadEdgeId;

    private String roadEdgeName;

    private Long sourceNodeId;

    private Long destinationNodeId;

    private BlockageReason blockageReason;

    private SeverityLevel impactSeverity;

    private LocalDateTime blockedAt;

    private LocalDateTime expectedClearTime;

    private Boolean active;

    private String remarks;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}