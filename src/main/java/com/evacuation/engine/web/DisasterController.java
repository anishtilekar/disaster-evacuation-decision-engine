package com.evacuation.engine.web;

import com.evacuation.engine.dto.common.ApiResponse;
import com.evacuation.engine.dto.disaster.request.DisasterCreateRequest;
import com.evacuation.engine.dto.disaster.request.DisasterZoneRequest;
import com.evacuation.engine.dto.disaster.response.DisasterResponse;
import com.evacuation.engine.dto.disaster.response.DisasterZoneResponse;
import com.evacuation.engine.service.DisasterService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The disaster/zone creation surface an evacuation request needs to reference. See
 * {@link DisasterService} for why this is create-only rather than the fuller CRUD surface the
 * existing update DTOs and mappers could otherwise support.
 */
@RestController
@RequestMapping("/api/disasters")
@RequiredArgsConstructor
public class DisasterController {

    private final DisasterService disasterService;

    /**
     * Records a new disaster.
     *
     * @param request the operator's input
     * @return {@code 201 Created} with the persisted disaster
     */
    @PostMapping
    public ResponseEntity<ApiResponse<DisasterResponse>> createDisaster(
            @Valid @RequestBody DisasterCreateRequest request) {

        DisasterResponse response = disasterService.createDisaster(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Disaster created", response));
    }

    /**
     * Records a new zone under an existing disaster.
     *
     * @param request the operator's input, carrying the owning disaster by id
     * @return {@code 201 Created} with the persisted zone
     */
    @PostMapping("/zones")
    public ResponseEntity<ApiResponse<DisasterZoneResponse>> createZone(
            @Valid @RequestBody DisasterZoneRequest request) {

        DisasterZoneResponse response = disasterService.createZone(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Disaster zone created", response));
    }
}
