package com.evacuation.engine.dto.graph.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.evacuation.engine.dto.common.GeoCoordinateDTO;
import com.evacuation.engine.model.enums.NodeType;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoadNodeRequest {

    @NotBlank(message = "Node name is required")
    @Size(max = 150, message = "Node name cannot exceed 150 characters")
    private String nodeName;

    @NotNull(message = "Node type is required")
    private NodeType nodeType;

    @NotNull(message = "Coordinate is required")
    private GeoCoordinateDTO coordinate;

    private Boolean active;
}