package com.evacuation.engine.mapper.disaster;

import com.evacuation.engine.dto.disaster.request.DisasterCreateRequest;
import com.evacuation.engine.dto.disaster.request.DisasterUpdateRequest;
import com.evacuation.engine.dto.disaster.response.DisasterResponse;
import com.evacuation.engine.dto.disaster.response.DisasterSummaryDTO;
import com.evacuation.engine.model.entity.Disaster;
import com.evacuation.engine.model.enums.DisasterStatus;
import com.evacuation.engine.model.enums.DisasterType;
import com.evacuation.engine.model.enums.SeverityLevel;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DisasterMapperTest {

    private final DisasterMapper mapper = new DisasterMapperImpl();

    @Test
    void toEntity_copiesRawLatLngDirectlyAndIgnoresServerManagedFields() {
        DisasterCreateRequest request = DisasterCreateRequest.builder()
                .disasterName("Mula-Mutha Flooding")
                .disasterType(DisasterType.FLOOD)
                .description("Heavy monsoon flooding along the riverbank")
                .severityLevel(SeverityLevel.HIGH)
                .startTime(LocalDateTime.of(2026, 7, 24, 6, 0))
                .affectedRegion("Kothrud-Karve Nagar")
                .latitude(18.5024)
                .longitude(73.8077)
                .impactRadius(3.5)
                .build();

        Disaster entity = mapper.toEntity(request);

        assertThat(entity.getDisasterName()).isEqualTo("Mula-Mutha Flooding");
        assertThat(entity.getDisasterType()).isEqualTo(DisasterType.FLOOD);
        assertThat(entity.getLatitude()).isEqualTo(18.5024);
        assertThat(entity.getLongitude()).isEqualTo(73.8077);
        assertThat(entity.getImpactRadius()).isEqualTo(3.5);

        assertThat(entity.getDisasterId()).isNull();
        assertThat(entity.getEndTime()).isNull();
        assertThat(entity.getStatus()).isNull();
        assertThat(entity.getCreatedAt()).isNull();
        assertThat(entity.getUpdatedAt()).isNull();
        assertThat(entity.getVersion()).isNull();
    }

    @Test
    void updateEntity_partialUpdateLeavesUnsetFieldsUntouched() {
        Disaster entity = Disaster.builder()
                .disasterId(7L)
                .disasterName("Original Name")
                .disasterType(DisasterType.FLOOD)
                .description("Original description")
                .severityLevel(SeverityLevel.MEDIUM)
                .status(DisasterStatus.ACTIVE)
                .affectedRegion("Original Region")
                .latitude(18.5)
                .longitude(73.8)
                .impactRadius(2.0)
                .build();

        DisasterUpdateRequest partialUpdate = DisasterUpdateRequest.builder()
                .severityLevel(SeverityLevel.CRITICAL)
                .status(DisasterStatus.CONTAINED)
                .build();

        mapper.updateEntity(entity, partialUpdate);

        assertThat(entity.getSeverityLevel()).isEqualTo(SeverityLevel.CRITICAL);
        assertThat(entity.getStatus()).isEqualTo(DisasterStatus.CONTAINED);

        assertThat(entity.getDisasterId()).isEqualTo(7L);
        assertThat(entity.getDisasterName()).isEqualTo("Original Name");
        assertThat(entity.getDescription()).isEqualTo("Original description");
        assertThat(entity.getAffectedRegion()).isEqualTo("Original Region");
        assertThat(entity.getLatitude()).isEqualTo(18.5);
        assertThat(entity.getLongitude()).isEqualTo(73.8);
        assertThat(entity.getImpactRadius()).isEqualTo(2.0);
    }

    @Test
    void toResponse_buildsLocationFromLatLng() {
        Disaster entity = Disaster.builder()
                .disasterId(9L)
                .disasterName("Pashan Hill Wildfire")
                .disasterType(DisasterType.WILDFIRE)
                .severityLevel(SeverityLevel.HIGH)
                .status(DisasterStatus.ACTIVE)
                .affectedRegion("Pashan")
                .latitude(18.5423)
                .longitude(73.7891)
                .impactRadius(1.2)
                .build();

        DisasterResponse response = mapper.toResponse(entity);

        assertThat(response.getDisasterId()).isEqualTo(9L);
        assertThat(response.getLocation()).isNotNull();
        assertThat(response.getLocation().latitude()).isEqualTo(18.5423);
        assertThat(response.getLocation().longitude()).isEqualTo(73.7891);
    }

    @Test
    void toSummary_mapsOnlySummaryFields() {
        Disaster entity = Disaster.builder()
                .disasterId(12L)
                .disasterName("Katraj Landslide")
                .disasterType(DisasterType.LANDSLIDE)
                .severityLevel(SeverityLevel.CRITICAL)
                .status(DisasterStatus.ACTIVE)
                .affectedRegion("Katraj")
                .latitude(18.4575)
                .longitude(73.8648)
                .impactRadius(0.8)
                .build();

        DisasterSummaryDTO summary = mapper.toSummary(entity);

        assertThat(summary.getDisasterId()).isEqualTo(12L);
        assertThat(summary.getDisasterName()).isEqualTo("Katraj Landslide");
        assertThat(summary.getDisasterType()).isEqualTo(DisasterType.LANDSLIDE);
        assertThat(summary.getSeverityLevel()).isEqualTo(SeverityLevel.CRITICAL);
        assertThat(summary.getStatus()).isEqualTo(DisasterStatus.ACTIVE);
        assertThat(summary.getAffectedRegion()).isEqualTo("Katraj");
    }
}
