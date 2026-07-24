package com.evacuation.engine.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.evacuation.engine.model.enums.DisasterStatus;
import com.evacuation.engine.model.enums.SeverityLevel;
import com.evacuation.engine.model.enums.RiskLevel;
import com.evacuation.engine.model.enums.RoadStatus;
import com.evacuation.engine.model.enums.ShelterStatus;
import com.evacuation.engine.model.enums.EvacuationStatus;
import com.evacuation.engine.model.enums.EvacuationPriority;
import com.evacuation.engine.model.enums.RouteStatus;
import com.evacuation.engine.model.enums.NotificationStatus;
import com.evacuation.engine.model.enums.NotificationType;

import java.time.LocalDateTime;
import java.util.Map;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardOverviewDTO {

    private LocalDateTime generatedAt;

    private DisasterOverview disasterOverview;

    private RoadNetworkOverview roadNetworkOverview;

    private ShelterOverview shelterOverview;

    private EvacuationOverview evacuationOverview;

    private NotificationOverview notificationOverview;

    public record DisasterOverview(
            long totalDisasters,
            long activeDisasters,
            Map<DisasterStatus, Long> disastersByStatus,
            Map<SeverityLevel, Long> disastersBySeverity,
            long totalDisasterZones,
            long zonesRequiringEvacuation,
            Map<RiskLevel, Long> zonesByRiskLevel
    ) {
    }

    public record RoadNetworkOverview(
            long totalRoadNodes,
            long activeRoadNodes,
            long totalRoadEdges,
            Map<RoadStatus, Long> roadEdgesByStatus,
            long totalBlockedRoads,
            long activeBlockedRoads
    ) {
    }

    public record ShelterOverview(
            long totalShelters,
            int totalCapacity,
            int totalOccupancy,
            int totalAvailableCapacity,
            Map<ShelterStatus, Long> sheltersByStatus,
            long sheltersWithMedicalFacility
    ) {
    }

    public record EvacuationOverview(
            long totalEvacuationRequests,
            Map<EvacuationStatus, Long> requestsByStatus,
            Map<EvacuationPriority, Long> requestsByPriority,
            long totalPeopleRequestingEvacuation,
            long totalEvacuationRoutes,
            Map<RouteStatus, Long> routesByStatus
    ) {
    }

    public record NotificationOverview(
            long totalNotifications,
            Map<NotificationStatus, Long> notificationsByStatus,
            Map<NotificationType, Long> notificationsByType
    ) {
    }
}