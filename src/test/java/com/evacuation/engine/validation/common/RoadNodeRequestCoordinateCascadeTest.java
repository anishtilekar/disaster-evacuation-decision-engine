package com.evacuation.engine.validation.common;

import com.evacuation.engine.dto.common.GeoCoordinateDTO;
import com.evacuation.engine.dto.graph.request.RoadNodeRequest;
import com.evacuation.engine.model.enums.NodeType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves @Valid on RoadNodeRequest.coordinate actually cascades into
 * GeoCoordinateDTO's class-level @ValidCoordinate constraint, rather than
 * just testing CoordinateValidator's logic in isolation.
 */
class RoadNodeRequestCoordinateCascadeTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    @Test
    void isValid_whenCoordinateWithinPuneBounds() {
        RoadNodeRequest request = RoadNodeRequest.builder()
                .nodeName("FC Road Junction")
                .nodeType(NodeType.INTERSECTION)
                .coordinate(new GeoCoordinateDTO(18.5236, 73.8478))
                .active(true)
                .build();

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void isInvalid_whenCoordinateOutsidePuneBounds() {
        RoadNodeRequest request = RoadNodeRequest.builder()
                .nodeName("Mumbai Junction")
                .nodeType(NodeType.INTERSECTION)
                .coordinate(new GeoCoordinateDTO(19.0760, 72.8777))
                .active(true)
                .build();

        Set<ConstraintViolation<RoadNodeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anyMatch(v -> v.getMessage().equals("Coordinate must be within the Pune pilot area"));
    }
}
