package com.evacuation.engine.config;

import com.evacuation.engine.model.*;
import com.evacuation.engine.repository.*;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

@Configuration
public class DataLoader {

    @Bean
    CommandLineRunner loadData(
            DisasterRepository disasterRepository,
            ZoneRepository zoneRepository,
            ShelterRepository shelterRepository,
            RouteRepository routeRepository,
            UserRepository userRepository,
            EvacuationRequestRepository requestRepository) {

        return args -> {

            // ======================
            // CREATE DISASTERS
            // ======================

            Disaster flood = new Disaster();
            flood.setDisasterName("Mumbai Flood 2026");
            flood.setDisasterType("Flood");
            flood.setDescription("Heavy monsoon flooding in Mumbai region");
            flood.setSeverityLevel("HIGH");
            flood.setStartTime(LocalDateTime.now());
            flood.setStatus("ACTIVE");
            flood.setAffectedRegion("Mumbai");
            flood.setLatitude(19.0760);
            flood.setLongitude(72.8777);
            flood.setImpactRadius(50);

            disasterRepository.save(flood);

            Disaster earthquake = new Disaster();
            earthquake.setDisasterName("Delhi Earthquake");
            earthquake.setDisasterType("Earthquake");
            earthquake.setDescription("Moderate earthquake detected");
            earthquake.setSeverityLevel("MEDIUM");
            earthquake.setStartTime(LocalDateTime.now());
            earthquake.setStatus("ACTIVE");
            earthquake.setAffectedRegion("Delhi");
            earthquake.setLatitude(28.7041);
            earthquake.setLongitude(77.1025);
            earthquake.setImpactRadius(120);

            disasterRepository.save(earthquake);


            // ======================
            // CREATE ZONES
            // ======================

            Zone zone1 = new Zone();
            zone1.setZoneName("South Mumbai");
            zone1.setRiskLevel("HIGH");
            zone1.setLatitude(18.96);
            zone1.setLongitude(72.82);
            zone1.setPopulation(500000);
            zone1.setEvacuationRequired(true);
            zone1.setDisaster(flood);

            zoneRepository.save(zone1);

            Zone zone2 = new Zone();
            zone2.setZoneName("Bandra");
            zone2.setRiskLevel("MEDIUM");
            zone2.setLatitude(19.0596);
            zone2.setLongitude(72.8295);
            zone2.setPopulation(300000);
            zone2.setEvacuationRequired(true);
            zone2.setDisaster(flood);

            zoneRepository.save(zone2);


            // ======================
            // CREATE SHELTERS
            // ======================

            Shelter shelter1 = new Shelter();
            shelter1.setShelterName("City Relief Camp");
            shelter1.setLocation("Pune");
            shelter1.setLatitude(18.5204);
            shelter1.setLongitude(73.8567);
            shelter1.setCapacity(2000);
            shelter1.setCurrentOccupancy(500);
            shelter1.setMedicalFacility(true);
            shelter1.setFoodSupply(true);
            shelter1.setWaterSupply(true);
            shelter1.setContactNumber("9876543210");

            shelterRepository.save(shelter1);

            Shelter shelter2 = new Shelter();
            shelter2.setShelterName("Government Shelter Camp");
            shelter2.setLocation("Navi Mumbai");
            shelter2.setLatitude(19.0330);
            shelter2.setLongitude(73.0297);
            shelter2.setCapacity(1500);
            shelter2.setCurrentOccupancy(300);
            shelter2.setMedicalFacility(true);
            shelter2.setFoodSupply(true);
            shelter2.setWaterSupply(true);
            shelter2.setContactNumber("9123456789");

            shelterRepository.save(shelter2);


            // ======================
            // CREATE ROUTES
            // ======================

            Route route1 = new Route();
            route1.setRouteName("Mumbai-Pune Expressway");
            route1.setStartLocation("Mumbai");
            route1.setEndLocation("Pune");
            route1.setDistanceKm(150);
            route1.setEstimatedTravelTime(120);
            route1.setBlocked(false);
            route1.setHighTraffic(true);
            route1.setRecommendedVehicle("Bus");

            routeRepository.save(route1);

            Route route2 = new Route();
            route2.setRouteName("Eastern Highway");
            route2.setStartLocation("Bandra");
            route2.setEndLocation("Navi Mumbai");
            route2.setDistanceKm(35);
            route2.setEstimatedTravelTime(50);
            route2.setBlocked(false);
            route2.setHighTraffic(false);
            route2.setRecommendedVehicle("Car");

            routeRepository.save(route2);


            // ======================
            // CREATE USERS (SAFE INSERT)
            // ======================

            User user1;

            if (userRepository.findByEmail("rahul@example.com").isEmpty()) {
                user1 = new User();
                user1.setFullName("Rahul Sharma");
                user1.setEmail("rahul@example.com");
                user1.setPhoneNumber("9876543211");
                user1.setPassword("password123");
                user1.setRole("CITIZEN");
                user1.setAddress("Mumbai");
                user1.setLatitude(19.0760);
                user1.setLongitude(72.8777);
                user1.setActive(true);

                userRepository.save(user1);
            } else {
                user1 = userRepository.findByEmail("rahul@example.com").get();
            }

            if (userRepository.findByEmail("admin@example.com").isEmpty()) {
                User user2 = new User();
                user2.setFullName("Admin User");
                user2.setEmail("admin@example.com");
                user2.setPhoneNumber("9000000000");
                user2.setPassword("admin123");
                user2.setRole("ADMIN");
                user2.setAddress("Delhi");
                user2.setLatitude(28.7041);
                user2.setLongitude(77.1025);
                user2.setActive(true);

                userRepository.save(user2);
            }


            // ======================
            // CREATE EVACUATION REQUEST
            // ======================

            EvacuationRequest request = new EvacuationRequest();
            request.setUser(user1);
            request.setZone(zone1);
            request.setAssignedShelter(shelter1);
            request.setRequestStatus("PENDING");
            request.setNumberOfPeople(4);
            request.setMedicalAssistanceNeeded(false);
            request.setTransportationNeeded(true);
            request.setRequestTime(LocalDateTime.now());

            requestRepository.save(request);

            System.out.println("Sample data loaded successfully!");
        };
    }
}