USE evacuation_engine;

-- =====================================================
-- DISASTERS
-- =====================================================

INSERT INTO disasters (name, disaster_type, severity_level, start_time, status, description) VALUES
('Pune Flood 2026', 'Flood', 5, '2026-07-12 08:00:00', 'ACTIVE', 'Heavy rainfall caused river overflow'),
('Western India Earthquake', 'Earthquake', 4, '2026-08-02 05:30:00', 'ACTIVE', 'Moderate earthquake affecting Maharashtra'),
('Forest Fire Pune District', 'Wildfire', 3, '2026-05-15 13:10:00', 'ACTIVE', 'Wildfire spreading near forest areas');

-- =====================================================
-- ZONES
-- =====================================================

INSERT INTO zones (zone_name, latitude, longitude, risk_level, disaster_id) VALUES
('Kothrud',18.5074,73.8077,4,1),
('Shivaji Nagar',18.5308,73.8475,5,1),
('Hadapsar',18.5912,73.9418,3,1),
('Wakad',18.5995,73.7636,2,1),
('Baner',18.5590,73.7868,3,1),
('Hinjewadi',18.5910,73.7389,2,2),
('Aundh',18.5600,73.8070,4,2),
('Pimpri',18.6298,73.7997,3,2),
('Chinchwad',18.6278,73.8130,2,2),
('Warje',18.4897,73.8071,4,3);

-- =====================================================
-- SHELTERS
-- =====================================================

INSERT INTO shelters (shelter_name, latitude, longitude, capacity, current_occupancy, zone_id, status) VALUES
('Pune Municipal School Shelter',18.5065,73.8090,500,120,1,'OPEN'),
('Shivaji Sports Complex Shelter',18.5320,73.8485,700,350,2,'OPEN'),
('Hadapsar Community Hall',18.5920,73.9425,400,90,3,'OPEN'),
('Wakad Public School Shelter',18.6005,73.7645,300,80,4,'OPEN'),
('Baner Stadium Shelter',18.5602,73.7875,450,200,5,'OPEN'),
('Hinjewadi IT Park Shelter',18.5920,73.7398,600,250,6,'OPEN'),
('Aundh College Shelter',18.5610,73.8080,350,140,7,'OPEN'),
('Pimpri Community Shelter',18.6305,73.8005,400,150,8,'OPEN'),
('Chinchwad School Shelter',18.6285,73.8140,300,120,9,'OPEN'),
('Warje Cultural Hall Shelter',18.4905,73.8080,350,110,10,'OPEN');

-- =====================================================
-- ROUTES
-- =====================================================

INSERT INTO routes (start_zone_id, end_shelter_id, distance_km, estimated_time_minutes, route_status) VALUES
(1,1,2.1,10,'OPEN'),
(2,2,1.2,6,'OPEN'),
(3,3,1.5,8,'OPEN'),
(4,4,2.4,11,'OPEN'),
(5,5,1.8,9,'OPEN'),
(6,6,1.1,5,'OPEN'),
(7,7,2.0,10,'OPEN'),
(8,8,1.6,7,'OPEN'),
(9,9,1.3,6,'OPEN'),
(10,10,1.7,8,'OPEN'),
(1,2,4.5,18,'CONGESTED'),
(2,3,6.1,22,'OPEN'),
(3,4,8.0,25,'OPEN'),
(4,5,4.2,17,'OPEN'),
(5,6,7.3,26,'OPEN');

-- =====================================================
-- USERS
-- =====================================================

INSERT INTO users (name, phone, role, latitude, longitude, zone_id) VALUES
('Rahul Patil','9876543210','CITIZEN',18.5070,73.8080,1),
('Sneha Kulkarni','9876543211','CITIZEN',18.5310,73.8480,2),
('Amit Deshmukh','9876543212','CITIZEN',18.5905,73.9420,3),
('Priya Joshi','9876543213','CITIZEN',18.6000,73.7640,4),
('Rohit Sharma','9876543214','CITIZEN',18.5595,73.7870,5),
('Anita Pawar','9876543215','CITIZEN',18.5920,73.7390,6),
('Vikram Singh','9876543216','CITIZEN',18.5605,73.8085,7),
('Pooja Nair','9876543217','CITIZEN',18.6300,73.8000,8),
('Karan Mehta','9876543218','CITIZEN',18.6280,73.8135,9),
('Neha Kapoor','9876543219','CITIZEN',18.4900,73.8075,10),
('Admin User','9999999999','ADMIN',18.5204,73.8567,2),
('Rescue Officer','8888888888','RESCUE_TEAM',18.5300,73.8450,2);

-- =====================================================
-- EVACUATION REQUESTS
-- =====================================================

INSERT INTO evacuation_requests (user_id, disaster_id, status, assigned_route_id, assigned_shelter_id) VALUES
(1,1,'PROCESSING',1,1),
(2,1,'COMPLETED',2,2),
(3,1,'PENDING',3,3),
(4,1,'PROCESSING',4,4),
(5,1,'COMPLETED',5,5),
(6,2,'PENDING',6,6),
(7,2,'PROCESSING',7,7),
(8,2,'COMPLETED',8,8),
(9,2,'PROCESSING',9,9),
(10,3,'PENDING',10,10);

-- =====================================================
-- ROUTE UPDATES
-- =====================================================

INSERT INTO route_updates (route_id, status, message) VALUES
(11,'CONGESTED','Heavy traffic reported'),
(12,'OPEN','Route cleared'),
(13,'OPEN','Safe route'),
(14,'OPEN','Normal traffic'),
(15,'OPEN','No issues reported');

-- =====================================================
-- RESCUE TEAMS
-- =====================================================

INSERT INTO rescue_teams (team_name, contact_number, base_zone_id, status) VALUES
('NDRF Team Alpha','9000000001',2,'ACTIVE'),
('Fire Brigade Unit 3','9000000002',3,'ACTIVE'),
('Medical Response Team','9000000003',5,'ACTIVE'),
('Police Emergency Unit','9000000004',1,'ACTIVE'),
('Volunteer Rescue Group','9000000005',4,'ACTIVE');

-- =====================================================
-- RESOURCES
-- =====================================================

INSERT INTO resources (resource_name, quantity, shelter_id) VALUES
('Food Packets',500,1),
('Water Bottles',800,1),
('Medical Kits',200,2),
('Blankets',300,3),
('Emergency Lights',150,4),
('Food Packets',600,5),
('Water Bottles',900,6),
('Medical Kits',250,7),
('Blankets',200,8),
('Emergency Lights',180,9);

-- =====================================================
-- ALERTS
-- =====================================================

INSERT INTO alerts (disaster_id, message, severity_level) VALUES
(1,'River water level rising rapidly',5),
(1,'Immediate evacuation advised in Shivaji Nagar',5),
(2,'Aftershocks expected',4),
(2,'Avoid damaged buildings',4),
(3,'Forest fire spreading towards residential areas',3),
(3,'Evacuate nearby villages immediately',3);