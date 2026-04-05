-- =====================================================
-- Disaster Evacuation Decision Engine Database
-- =====================================================

-- Create Database
CREATE DATABASE IF NOT EXISTS evacuation_engine;
USE evacuation_engine;

-- =====================================================
-- DISASTERS TABLE
-- =====================================================

CREATE TABLE disasters (
    disaster_id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    disaster_type VARCHAR(50),
    severity_level INT,
    start_time DATETIME,
    status VARCHAR(50),
    description TEXT
);

-- =====================================================
-- ZONES TABLE
-- =====================================================

CREATE TABLE zones (
    zone_id INT AUTO_INCREMENT PRIMARY KEY,
    zone_name VARCHAR(100) NOT NULL,
    latitude DECIMAL(10,7),
    longitude DECIMAL(10,7),
    risk_level INT,
    disaster_id INT,
    FOREIGN KEY (disaster_id) REFERENCES disasters(disaster_id)
);

-- =====================================================
-- SHELTERS TABLE
-- =====================================================

CREATE TABLE shelters (
    shelter_id INT AUTO_INCREMENT PRIMARY KEY,
    shelter_name VARCHAR(150) NOT NULL,
    latitude DECIMAL(10,7),
    longitude DECIMAL(10,7),
    capacity INT,
    current_occupancy INT DEFAULT 0,
    zone_id INT,
    status VARCHAR(50),
    FOREIGN KEY (zone_id) REFERENCES zones(zone_id)
);

-- =====================================================
-- ROUTES TABLE
-- =====================================================

CREATE TABLE routes (
    route_id INT AUTO_INCREMENT PRIMARY KEY,
    start_zone_id INT,
    end_shelter_id INT,
    distance_km DECIMAL(6,2),
    estimated_time_minutes INT,
    route_status VARCHAR(50),
    FOREIGN KEY (start_zone_id) REFERENCES zones(zone_id),
    FOREIGN KEY (end_shelter_id) REFERENCES shelters(shelter_id)
);

-- =====================================================
-- USERS TABLE
-- =====================================================

CREATE TABLE users (
    user_id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    phone VARCHAR(20),
    role VARCHAR(50),
    latitude DECIMAL(10,7),
    longitude DECIMAL(10,7),
    zone_id INT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (zone_id) REFERENCES zones(zone_id)
);

-- =====================================================
-- EVACUATION REQUESTS TABLE
-- =====================================================

CREATE TABLE evacuation_requests (
    request_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT,
    disaster_id INT,
    request_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(50),
    assigned_route_id INT,
    assigned_shelter_id INT,
    FOREIGN KEY (user_id) REFERENCES users(user_id),
    FOREIGN KEY (disaster_id) REFERENCES disasters(disaster_id),
    FOREIGN KEY (assigned_route_id) REFERENCES routes(route_id),
    FOREIGN KEY (assigned_shelter_id) REFERENCES shelters(shelter_id)
);

-- =====================================================
-- ROUTE UPDATES TABLE
-- =====================================================

CREATE TABLE route_updates (
    update_id INT AUTO_INCREMENT PRIMARY KEY,
    route_id INT,
    status VARCHAR(50),
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    message TEXT,
    FOREIGN KEY (route_id) REFERENCES routes(route_id)
);

-- =====================================================
-- RESCUE TEAMS TABLE
-- =====================================================

CREATE TABLE rescue_teams (
    team_id INT AUTO_INCREMENT PRIMARY KEY,
    team_name VARCHAR(100),
    contact_number VARCHAR(20),
    base_zone_id INT,
    status VARCHAR(50),
    FOREIGN KEY (base_zone_id) REFERENCES zones(zone_id)
);

-- =====================================================
-- RESOURCE TABLE
-- =====================================================

CREATE TABLE resources (
    resource_id INT AUTO_INCREMENT PRIMARY KEY,
    resource_name VARCHAR(100),
    quantity INT,
    shelter_id INT,
    FOREIGN KEY (shelter_id) REFERENCES shelters(shelter_id)
);

-- =====================================================
-- ALERTS TABLE
-- =====================================================

CREATE TABLE alerts (
    alert_id INT AUTO_INCREMENT PRIMARY KEY,
    disaster_id INT,
    message TEXT,
    alert_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    severity_level INT,
    FOREIGN KEY (disaster_id) REFERENCES disasters(disaster_id)
);

-- =====================================================
-- INDEXES FOR PERFORMANCE
-- =====================================================

CREATE INDEX idx_zone_disaster 
ON zones(disaster_id);

CREATE INDEX idx_shelter_zone 
ON shelters(zone_id);

CREATE INDEX idx_route_zone 
ON routes(start_zone_id);

CREATE INDEX idx_user_zone 
ON users(zone_id);

CREATE INDEX idx_request_user 
ON evacuation_requests(user_id);

CREATE INDEX idx_request_disaster 
ON evacuation_requests(disaster_id);