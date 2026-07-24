package com.evacuation.engine.dto.graph.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.evacuation.engine.model.enums.BlockageReason;
import com.evacuation.engine.model.enums.SeverityLevel;
import com.evacuation.engine.validation.graph.ValidBlockedRoadTimeRange;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ValidBlockedRoadTimeRange
public class BlockedRoadRequest {

    @NotNull(message = "Disaster ID is required")
    private Long disasterId;

    @NotNull(message = "Road edge ID is required")
    private Long roadEdgeId;

    @NotNull(message = "Blockage reason is required")
    private BlockageReason blockageReason;

    private SeverityLevel impactSeverity;

    private LocalDateTime blockedAt;

    private LocalDateTime expectedClearTime;

    private Boolean active;

    @Size(max = 500, message = "Remarks cannot exceed 500 characters")
    private String remarks;
}