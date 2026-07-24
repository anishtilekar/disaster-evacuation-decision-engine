package com.evacuation.engine.mapper.disaster;

import com.evacuation.engine.dto.disaster.request.DisasterZoneRequest;
import com.evacuation.engine.dto.disaster.response.DisasterZoneResponse;
import com.evacuation.engine.model.entity.Disaster;
import com.evacuation.engine.model.entity.DisasterZone;
import com.evacuation.engine.model.enums.DisasterType;
import com.evacuation.engine.model.enums.RiskLevel;
import com.evacuation.engine.model.enums.SeverityLevel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DisasterZoneMapperTest {

    private final DisasterZoneMapper mapper = new DisasterZoneMapperImpl();

    @Test
    void toEntity_copiesRawLatLngDirectlyAndIgnoresDisasterRelation() {
        DisasterZoneRequest request = DisasterZoneRequest.builder()
                .disasterId(3L)
                .zoneName("Sinhagad Road Zone A")
                .riskLevel(RiskLevel.HIGH)
                .latitude(18.4634)
                .longitude(73.8231)
                .population(1500)
                .evacuationRequired(true)
                .build();

        DisasterZone entity = mapper.toEntity(request);

        assertThat(entity.getZoneName()).isEqualTo("Sinhagad Road Zone A");
        assertThat(entity.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(entity.getLatitude()).isEqualTo(18.4634);
        assertThat(entity.getLongitude()).isEqualTo(73.8231);
        assertThat(entity.getPopulation()).isEqualTo(1500);
        assertThat(entity.getEvacuationRequired()).isTrue();

        assertThat(entity.getZoneId()).isNull();
        assertThat(entity.getDisaster()).isNull();
        assertThat(entity.getCreatedAt()).isNull();
        assertThat(entity.getUpdatedAt()).isNull();
        assertThat(entity.getVersion()).isNull();
    }

    @Test
    void toResponse_flattensDisasterRelationAndBuildsLocation() {
        Disaster disaster = Disaster.builder()
                .disasterId(3L)
                .disasterName("Sinhagad Road Flooding")
                .disasterType(DisasterType.FLOOD)
                .severityLevel(SeverityLevel.HIGH)
                .build();

        DisasterZone entity = DisasterZone.builder()
                .zoneId(20L)
                .zoneName("Sinhagad Road Zone A")
                .riskLevel(RiskLevel.HIGH)
                .latitude(18.4634)
                .longitude(73.8231)
                .population(1500)
                .evacuationRequired(true)
                .disaster(disaster)
                .build();

        DisasterZoneResponse response = mapper.toResponse(entity);

        assertThat(response.getZoneId()).isEqualTo(20L);
        assertThat(response.getZoneName()).isEqualTo("Sinhagad Road Zone A");
        assertThat(response.getLocation()).isNotNull();
        assertThat(response.getLocation().latitude()).isEqualTo(18.4634);
        assertThat(response.getLocation().longitude()).isEqualTo(73.8231);
        assertThat(response.getDisasterId()).isEqualTo(3L);
        assertThat(response.getDisasterName()).isEqualTo("Sinhagad Road Flooding");
    }

    @Test
    void toResponse_disasterFieldsAreNullWhenRelationIsNull() {
        DisasterZone entity = DisasterZone.builder()
                .zoneId(21L)
                .zoneName("Unlinked Zone")
                .riskLevel(RiskLevel.LOW)
                .latitude(18.0)
                .longitude(73.0)
                .population(0)
                .evacuationRequired(false)
                .disaster(null)
                .build();

        DisasterZoneResponse response = mapper.toResponse(entity);

        assertThat(response.getDisasterId()).isNull();
        assertThat(response.getDisasterName()).isNull();
    }
}
