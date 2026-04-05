-- =====================================================
-- DISASTER EVACUATION DECISION ENGINE
-- Decision Queries File
-- =====================================================

USE evacuation_engine;

-- =====================================================
-- 1. FIND ALL ACTIVE DISASTERS
-- =====================================================

SELECT *
FROM disasters
WHERE status = 'ACTIVE';


-- =====================================================
-- 2. FIND HIGH RISK ZONES
-- =====================================================

SELECT 
    zone_id,
    zone_name,
    risk_level
FROM zones
WHERE risk_level >= 4
ORDER BY risk_level DESC;


-- =====================================================
-- 3. FIND SHELTERS WITH AVAILABLE CAPACITY
-- =====================================================

SELECT 
    shelter_id,
    shelter_name,
    capacity,
    current_occupancy,
    (capacity - current_occupancy) AS available_space
FROM shelters
WHERE status = 'OPEN'
AND capacity > current_occupancy
ORDER BY available_space DESC;


-- =====================================================
-- 4. FIND ALL SAFE ROUTES
-- =====================================================

SELECT 
    r.route_id,
    z.zone_name,
    s.shelter_name,
    r.distance_km,
    r.estimated_time_minutes
FROM routes r
JOIN zones z 
    ON r.start_zone_id = z.zone_id
JOIN shelters s 
    ON r.end_shelter_id = s.shelter_id
WHERE r.route_status = 'OPEN'
ORDER BY r.distance_km ASC;


-- =====================================================
-- 5. FIND NEAREST SHELTER FROM A ZONE
-- Example zone_id = 1
-- =====================================================

SELECT 
    s.shelter_name,
    r.distance_km,
    r.estimated_time_minutes
FROM routes r
JOIN shelters s 
    ON r.end_shelter_id = s.shelter_id
WHERE r.start_zone_id = 1
AND r.route_status = 'OPEN'
ORDER BY r.distance_km ASC
LIMIT 1;


-- =====================================================
-- 6. FIND BEST SHELTER (DISTANCE + CAPACITY)
-- =====================================================

SELECT 
    s.shelter_name,
    r.distance_km,
    (s.capacity - s.current_occupancy) AS available_space
FROM routes r
JOIN shelters s 
    ON r.end_shelter_id = s.shelter_id
WHERE r.start_zone_id = 1
AND r.route_status = 'OPEN'
AND s.status = 'OPEN'
AND s.capacity > s.current_occupancy
ORDER BY r.distance_km ASC, available_space DESC
LIMIT 1;


-- =====================================================
-- 7. COUNT PEOPLE NEEDING EVACUATION
-- =====================================================

SELECT 
    COUNT(*) AS pending_requests
FROM evacuation_requests
WHERE status = 'PENDING';


-- =====================================================
-- 8. MONITOR SHELTER OCCUPANCY
-- =====================================================

SELECT 
    shelter_name,
    capacity,
    current_occupancy,
    ROUND((current_occupancy / capacity) * 100,2) 
    AS occupancy_percentage
FROM shelters
ORDER BY occupancy_percentage DESC;


-- =====================================================
-- 9. FIND ROUTES WITH TRAFFIC OR BLOCKAGES
-- =====================================================

SELECT 
    r.route_id,
    z.zone_name,
    s.shelter_name,
    ru.status,
    ru.message
FROM route_updates ru
JOIN routes r 
    ON ru.route_id = r.route_id
JOIN zones z 
    ON r.start_zone_id = z.zone_id
JOIN shelters s 
    ON r.end_shelter_id = s.shelter_id;


-- =====================================================
-- 10. FIND RESCUE TEAMS NEAR A ZONE
-- =====================================================

SELECT 
    team_name,
    contact_number,
    status
FROM rescue_teams
WHERE base_zone_id = 1
AND status = 'ACTIVE';


-- =====================================================
-- 11. VIEW EVACUATION REQUEST STATUS
-- =====================================================

SELECT 
    u.name,
    er.status,
    s.shelter_name
FROM evacuation_requests er
JOIN users u 
    ON er.user_id = u.user_id
JOIN shelters s 
    ON er.assigned_shelter_id = s.shelter_id;


-- =====================================================
-- 12. GET ALL ACTIVE ALERTS
-- =====================================================

SELECT 
    a.message,
    a.severity_level,
    d.name AS disaster
FROM alerts a
JOIN disasters d 
    ON a.disaster_id = d.disaster_id
ORDER BY severity_level DESC;


-- =====================================================
-- 13. FIND SAFEST SHELTER WITH MOST SPACE
-- =====================================================

SELECT 
    shelter_name,
    capacity,
    current_occupancy,
    (capacity - current_occupancy) AS available_space
FROM shelters
WHERE status = 'OPEN'
ORDER BY available_space DESC
LIMIT 1;


-- =====================================================
-- 14. COMPLETE EVACUATION RECOMMENDATION
-- Core Decision Engine Query
-- =====================================================

SELECT 
    z.zone_name,
    s.shelter_name,
    r.distance_km,
    r.estimated_time_minutes,
    (s.capacity - s.current_occupancy) AS available_space
FROM routes r
JOIN zones z 
    ON r.start_zone_id = z.zone_id
JOIN shelters s 
    ON r.end_shelter_id = s.shelter_id
WHERE z.zone_id = 1
AND r.route_status = 'OPEN'
AND s.status = 'OPEN'
AND s.capacity > s.current_occupancy
ORDER BY r.distance_km ASC
LIMIT 1;