package com.evacuation.engine.validation.graph;

import com.evacuation.engine.dto.graph.request.BlockedRoadRequest;
import com.evacuation.engine.model.enums.BlockageReason;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves @ValidBlockedRoadTimeRange is actually wired onto BlockedRoadRequest.
 */
class BlockedRoadRequestValidationTest {

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

    private BlockedRoadRequest.BlockedRoadRequestBuilder validBuilder() {
        return BlockedRoadRequest.builder()
                .disasterId(1L)
                .roadEdgeId(2L)
                .blockageReason(BlockageReason.FLOODING);
    }

    @Test
    void isValid_whenExpectedClearTimeAfterBlockedAt() {
        BlockedRoadRequest request = validBuilder()
                .blockedAt(LocalDateTime.of(2026, 7, 24, 6, 0))
                .expectedClearTime(LocalDateTime.of(2026, 7, 24, 12, 0))
                .build();

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void isInvalid_whenExpectedClearTimeBeforeBlockedAt() {
        BlockedRoadRequest request = validBuilder()
                .blockedAt(LocalDateTime.of(2026, 7, 24, 12, 0))
                .expectedClearTime(LocalDateTime.of(2026, 7, 24, 6, 0))
                .build();

        Set<ConstraintViolation<BlockedRoadRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anyMatch(v -> v.getMessage().equals("Expected clear time must not be before blocked-at time"));
    }

    @Test
    void isValid_whenTimeFieldsAreNull() {
        BlockedRoadRequest request = validBuilder().build();

        assertThat(validator.validate(request)).isEmpty();
    }
}
