package com.evacuation.engine.dto.graph.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.evacuation.engine.dto.common.GeoCoordinateDTO;
import com.evacuation.engine.model.enums.NodeType;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoadNodeResponse {

    private Long nodeId;

    private String nodeName;

    private NodeType nodeType;

    private GeoCoordinateDTO coordinate;

    private Boolean active;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}