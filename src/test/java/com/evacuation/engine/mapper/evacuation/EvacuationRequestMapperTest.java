package com.evacuation.engine.mapper.evacuation;

import com.evacuation.engine.dto.evacuation.request.EvacuationRequestDTO;
import com.evacuation.engine.dto.evacuation.response.EvacuationRequestResponse;
import com.evacuation.engine.model.entity.Disaster;
import com.evacuation.engine.model.entity.DisasterZone;
import com.evacuation.engine.model.entity.EvacuationRequest;
import com.evacuation.engine.model.enums.DisasterType;
import com.evacuation.engine.model.enums.EvacuationPriority;
import com.evacuation.engine.model.enums.EvacuationStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvacuationRequestMapperTest {

    private final EvacuationRequestMapper mapper = new EvacuationRequestMapperImpl();

    @Test
    void toEntity_ignoresRelationsAndServerManagedFields() {
        EvacuationRequestDTO request = EvacuationRequestDTO.builder()
                .disasterId(1L)
                .disasterZoneId(2L)
                .requesterName("Anish Tilekar")
                .contactNumber("+919876543210")
                .numberOfPeople(4)
                .priority(EvacuationPriority.HIGH)
                .emergencyNotes("Elderly family member, needs wheelchair access")
                .medicalAssistanceRequired(true)
                .build();

        EvacuationRequest entity = mapper.toEntity(request);

        assertThat(entity.getRequesterName()).isEqualTo("Anish Tilekar");
        assertThat(entity.getContactNumber()).isEqualTo("+919876543210");
        assertThat(entity.getNumberOfPeople()).isEqualTo(4);
        assertThat(entity.getPriority()).isEqualTo(EvacuationPriority.HIGH);
        assertThat(entity.getMedicalAssistanceRequired()).isTrue();

        assertThat(entity.getEvacuationRequestId()).isNull();
        assertThat(entity.getDisaster()).isNull();
        assertThat(entity.getDisasterZone()).isNull();
        assertThat(entity.getSourceRoadNode()).isNull();
        assertThat(entity.getStatus()).isNull();
        assertThat(entity.getRequestedAt()).isNull();
        assertThat(entity.getCreatedAt()).isNull();
        assertThat(entity.getUpdatedAt()).isNull();
        assertThat(entity.getVersion()).isNull();
    }

    @Test
    void toResponse_flattensDisasterAndDisasterZoneRelations() {
        Disaster disaster = Disaster.builder()
                .disasterId(1L)
                .disasterName("Mula River Flooding")
                .disasterType(DisasterType.FLOOD)
                .build();

        DisasterZone zone = DisasterZone.builder()
                .zoneId(2L)
                .zoneName("Kothrud Zone B")
                .build();

        EvacuationRequest entity = EvacuationRequest.builder()
                .evacuationRequestId(50L)
                .disaster(disaster)
                .disasterZone(zone)
                .requesterName("Anish Tilekar")
                .contactNumber("+919876543210")
                .numberOfPeople(4)
                .priority(EvacuationPriority.HIGH)
                .status(EvacuationStatus.PENDING)
                .medicalAssistanceRequired(true)
                .build();

        EvacuationRequestResponse response = mapper.toResponse(entity);

        assertThat(response.getEvacuationRequestId()).isEqualTo(50L);
        assertThat(response.getDisasterId()).isEqualTo(1L);
        assertThat(response.getDisasterName()).isEqualTo("Mula River Flooding");
        assertThat(response.getDisasterZoneId()).isEqualTo(2L);
        assertThat(response.getZoneName()).isEqualTo("Kothrud Zone B");
        assertThat(response.getStatus()).isEqualTo(EvacuationStatus.PENDING);
    }

    @Test
    void toResponse_flattenedFieldsAreNullWhenRelationsAreNull() {
        EvacuationRequest entity = EvacuationRequest.builder()
                .evacuationRequestId(51L)
                .disaster(null)
                .disasterZone(null)
                .requesterName("Unlinked Requester")
                .contactNumber("+919876543211")
                .numberOfPeople(1)
                .priority(EvacuationPriority.LOW)
                .status(EvacuationStatus.PENDING)
                .medicalAssistanceRequired(false)
                .build();

        EvacuationRequestResponse response = mapper.toResponse(entity);

        assertThat(response.getDisasterId()).isNull();
        assertThat(response.getDisasterName()).isNull();
        assertThat(response.getDisasterZoneId()).isNull();
        assertThat(response.getZoneName()).isNull();
    }
}
