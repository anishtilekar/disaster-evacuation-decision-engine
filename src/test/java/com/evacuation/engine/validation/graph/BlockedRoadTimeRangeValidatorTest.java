package com.evacuation.engine.validation.graph;

import com.evacuation.engine.dto.graph.request.BlockedRoadRequest;
import com.evacuation.engine.model.enums.BlockageReason;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class BlockedRoadTimeRangeValidatorTest {

    private final BlockedRoadTimeRangeValidator validator = new BlockedRoadTimeRangeValidator();

    @Test
    void isValid_returnsTrueWhenExpectedClearTimeAfterBlockedAt() {
        BlockedRoadRequest request = BlockedRoadRequest.builder()
                .disasterId(1L)
                .roadEdgeId(2L)
                .blockageReason(BlockageReason.FLOODING)
                .blockedAt(LocalDateTime.of(2026, 7, 24, 6, 0))
                .expectedClearTime(LocalDateTime.of(2026, 7, 24, 12, 0))
                .build();

        assertThat(validator.isValid(request, null)).isTrue();
    }

    @Test
    void isValid_returnsFalseWhenExpectedClearTimeBeforeBlockedAt() {
        BlockedRoadRequest request = BlockedRoadRequest.builder()
                .disasterId(1L)
                .roadEdgeId(2L)
                .blockageReason(BlockageReason.FLOODING)
                .blockedAt(LocalDateTime.of(2026, 7, 24, 12, 0))
                .expectedClearTime(LocalDateTime.of(2026, 7, 24, 6, 0))
                .build();

        assertThat(validator.isValid(request, null)).isFalse();
    }

    @Test
    void isValid_returnsTrueWhenBlockedAtIsNull() {
        BlockedRoadRequest request = BlockedRoadRequest.builder()
                .disasterId(1L)
                .roadEdgeId(2L)
                .blockageReason(BlockageReason.FLOODING)
                .expectedClearTime(LocalDateTime.of(2026, 7, 24, 6, 0))
                .build();

        assertThat(validator.isValid(request, null)).isTrue();
    }

    @Test
    void isValid_returnsTrueWhenExpectedClearTimeIsNull() {
        BlockedRoadRequest request = BlockedRoadRequest.builder()
                .disasterId(1L)
                .roadEdgeId(2L)
                .blockageReason(BlockageReason.FLOODING)
                .blockedAt(LocalDateTime.of(2026, 7, 24, 6, 0))
                .build();

        assertThat(validator.isValid(request, null)).isTrue();
    }

    @Test
    void isValid_returnsTrueWhenValueItselfIsNull() {
        assertThat(validator.isValid(null, null)).isTrue();
    }
}
