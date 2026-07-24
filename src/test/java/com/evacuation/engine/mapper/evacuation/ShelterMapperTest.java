package com.evacuation.engine.mapper.evacuation;

import com.evacuation.engine.dto.common.GeoCoordinateDTO;
import com.evacuation.engine.dto.evacuation.request.ShelterRequestDTO;
import com.evacuation.engine.dto.evacuation.response.ShelterResponse;
import com.evacuation.engine.model.entity.Shelter;
import com.evacuation.engine.model.enums.ShelterStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ShelterMapperTest {

    private final ShelterMapper mapper = new ShelterMapperImpl();

    @Test
    void toResponse_mapsFieldsBuildsCoordinateAndComputesAvailableCapacity() {
        Shelter entity = Shelter.builder()
                .shelterId(5L)
                .shelterName("Community Hall Kothrud")
                .location("Kothrud Depot Road")
                .latitude(18.5074)
                .longitude(73.8077)
                .capacity(200)
                .currentOccupancy(150)
                .medicalFacility(true)
                .foodSupply(true)
                .waterSupply(false)
                .contactNumber("+919876543210")
                .shelterStatus(ShelterStatus.AVAILABLE)
                .createdAt(LocalDateTime.of(2026, 1, 1, 8, 0))
                .updatedAt(LocalDateTime.of(2026, 1, 2, 8, 0))
                .build();

        ShelterResponse response = mapper.toResponse(entity);

        assertThat(response.getShelterId()).isEqualTo(5L);
        assertThat(response.getShelterName()).isEqualTo("Community Hall Kothrud");
        assertThat(response.getCoordinate()).isNotNull();
        assertThat(response.getCoordinate().latitude()).isEqualTo(18.5074);
        assertThat(response.getCoordinate().longitude()).isEqualTo(73.8077);
        assertThat(response.getCapacity()).isEqualTo(200);
        assertThat(response.getCurrentOccupancy()).isEqualTo(150);
        assertThat(response.getAvailableCapacity()).isEqualTo(50);
        assertThat(response.getShelterStatus()).isEqualTo(ShelterStatus.AVAILABLE);
    }

    @Test
    void toEntity_splitsCoordinateAndIgnoresServerManagedFields() {
        ShelterRequestDTO request = ShelterRequestDTO.builder()
                .shelterName("Baner Relief Center")
                .location("Baner Road")
                .coordinate(new GeoCoordinateDTO(18.5590, 73.7868))
                .capacity(100)
                .medicalFacility(false)
                .foodSupply(true)
                .waterSupply(true)
                .contactNumber("+919812345678")
                .build();

        Shelter entity = mapper.toEntity(request);

        assertThat(entity.getShelterName()).isEqualTo("Baner Relief Center");
        assertThat(entity.getLatitude()).isEqualTo(18.5590);
        assertThat(entity.getLongitude()).isEqualTo(73.7868);
        assertThat(entity.getCapacity()).isEqualTo(100);
        assertThat(entity.getContactNumber()).isEqualTo("+919812345678");

        assertThat(entity.getShelterId()).isNull();
        assertThat(entity.getCurrentOccupancy()).isNull();
        assertThat(entity.getShelterStatus()).isNull();
        assertThat(entity.getCreatedAt()).isNull();
        assertThat(entity.getUpdatedAt()).isNull();
        assertThat(entity.getVersion()).isNull();
    }
}
