# 🚨 Disaster Evacuation Decision Engine

![Java](https://img.shields.io/badge/Java-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white)
![MySQL](https://img.shields.io/badge/MySQL-005C84?style=for-the-badge&logo=mysql&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-C71A36?style=for-the-badge&logo=apache-maven&logoColor=white)
![Hibernate](https://img.shields.io/badge/Hibernate-59666C?style=for-the-badge&logo=hibernate&logoColor=white)
![REST API](https://img.shields.io/badge/REST-API-blue?style=for-the-badge)

> A robust Java Spring Boot backend system for intelligent disaster evacuation planning and emergency management.

---

## 📖 Project Overview

The **Disaster Evacuation Decision Engine** is a backend system built to support real-time disaster response and evacuation coordination. It provides a structured REST API layer that empowers frontend dashboards or emergency management systems to efficiently manage disasters, at-risk zones, available shelters, evacuation routes, registered users, and live evacuation requests.

The system is designed with scalability and clarity in mind — following a clean, layered architecture that separates concerns across models, repositories, services, and controllers. On startup, it automatically seeds the database with sample data to allow instant testing and integration.

Whether used by city authorities, disaster response teams, or integrated into a larger emergency management platform, this engine provides a reliable decision-support backbone during critical situations.

---

## ✅ Features

- 🌋 **Disaster Management** — Create, retrieve, update, and delete disaster records
- 🗺️ **Risk Zone Management** — Track and manage geographical zones affected by disasters
- 🏠 **Shelter Management** — Maintain shelter capacity, location, and availability data
- 🛣️ **Evacuation Route Management** — Define and manage safe evacuation routes
- 👤 **User Registration** — Register citizens and emergency personnel
- 📋 **Evacuation Request Handling** — Submit and track evacuation requests in real time
- 🔌 **REST API Endpoints** — Full CRUD API coverage for all major entities
- 🗄️ **Auto Data Seeding** — Sample data is automatically loaded on application startup via `CommandLineRunner`
- 💾 **Dual Database Support** — Compatible with both MySQL (production) and H2 (development/testing)

---

## 🏛️ System Architecture

The project follows a clean **4-layer backend architecture**:

```
┌───────────────────────────────────────────┐
│              Client / Frontend            │
│     (Dashboard / Emergency System)        │
└──────────────────┬────────────────────────┘
                   │ HTTP Requests
                   ▼
┌───────────────────────────────────────────┐
│           Controller Layer                │
│   REST Controllers — Expose API Endpoints │
└──────────────────┬────────────────────────┘
                   │
                   ▼
┌───────────────────────────────────────────┐
│             Service Layer                 │
│   Business Logic & Validation             │
└──────────────────┬────────────────────────┘
                   │
                   ▼
┌───────────────────────────────────────────┐
│           Repository Layer                │
│   Spring Data JPA — Database Access       │
└──────────────────┬────────────────────────┘
                   │
                   ▼
┌───────────────────────────────────────────┐
│             Model Layer                   │
│   JPA Entities — Database Table Mapping   │
└───────────────────────────────────────────┘
```

| Layer | Responsibility |
|---|---|
| **Controller** | Handles incoming HTTP requests and returns responses |
| **Service** | Encapsulates business logic and orchestrates operations |
| **Repository** | Interfaces with the database using Spring Data JPA |
| **Model** | Defines JPA entities that map to database tables |

---

## 🛠️ Tech Stack

| Technology | Purpose |
|---|---|
| **Java** | Core programming language |
| **Spring Boot** | Application framework and auto-configuration |
| **Spring Data JPA** | Simplified database access and ORM abstraction |
| **Hibernate** | JPA implementation for entity management |
| **Maven** | Dependency management and build tool |
| **MySQL** | Primary relational database (production) |
| **H2 Database** | In-memory database for development and testing |
| **REST APIs** | Communication interface for clients |

---

## 📁 Project Structure

```
src/
└── main/
    └── java/
        └── com/
            └── evacuation/
                └── engine/
                    ├── model/                  # JPA Entity classes
                    │   ├── Disaster.java
                    │   ├── Zone.java
                    │   ├── Shelter.java
                    │   ├── Route.java
                    │   ├── User.java
                    │   └── EvacuationRequest.java
                    │
                    ├── repository/             # Spring Data JPA Repositories
                    │   ├── DisasterRepository.java
                    │   ├── ZoneRepository.java
                    │   ├── ShelterRepository.java
                    │   ├── RouteRepository.java
                    │   ├── UserRepository.java
                    │   └── EvacuationRequestRepository.java
                    │
                    ├── service/                # Business Logic Layer
                    │   ├── DisasterService.java
                    │   ├── ZoneService.java
                    │   ├── ShelterService.java
                    │   ├── RouteService.java
                    │   ├── UserService.java
                    │   └── EvacuationRequestService.java
                    │
                    ├── controller/             # REST API Controllers
                    │   ├── DisasterController.java
                    │   ├── ZoneController.java
                    │   ├── ShelterController.java
                    │   ├── RouteController.java
                    │   ├── UserController.java
                    │   └── EvacuationRequestController.java
                    │
                    └── config/
                        └── DataLoader.java     # Seeds sample data on startup
```

---

## 🗄️ Database Entities

### 🌋 Disaster
Represents a natural or man-made disaster event.

| Field | Type | Description |
|---|---|---|
| `id` | Long | Primary key |
| `name` | String | Name of the disaster |
| `type` | String | Category (e.g., Flood, Earthquake) |
| `severity` | String | Severity level (Low / Medium / High) |
| `location` | String | Affected location or region |

---

### 🗺️ Zone
Represents a geographical area affected by a disaster.

| Field | Type | Description |
|---|---|---|
| `id` | Long | Primary key |
| `name` | String | Zone identifier or name |
| `riskLevel` | String | Risk classification (e.g., Red, Orange, Green) |
| `disaster` | Disaster | Associated disaster event |

---

### 🏠 Shelter
Represents an available emergency shelter.

| Field | Type | Description |
|---|---|---|
| `id` | Long | Primary key |
| `name` | String | Shelter name |
| `location` | String | Physical address or coordinates |
| `capacity` | Integer | Maximum occupancy |
| `available` | Boolean | Whether shelter is currently accepting evacuees |

---

### 🛣️ Route
Represents a designated evacuation route.

| Field | Type | Description |
|---|---|---|
| `id` | Long | Primary key |
| `name` | String | Route name or identifier |
| `startPoint` | String | Starting location |
| `endPoint` | String | Destination or shelter endpoint |
| `status` | String | Route status (Open / Blocked / Congested) |

---

### 👤 User
Represents a registered citizen or emergency personnel.

| Field | Type | Description |
|---|---|---|
| `id` | Long | Primary key |
| `name` | String | Full name |
| `email` | String | Unique email address |
| `role` | String | Role (e.g., Citizen, Admin, Responder) |
| `phone` | String | Contact number |

---

### 📋 EvacuationRequest
Represents a request submitted by a user to be evacuated.

| Field | Type | Description |
|---|---|---|
| `id` | Long | Primary key |
| `user` | User | Requesting user |
| `zone` | Zone | Zone from which evacuation is needed |
| `shelter` | Shelter | Target shelter |
| `status` | String | Request status (Pending / Approved / Completed) |
| `requestedAt` | DateTime | Timestamp of the request |

---

## 🔌 API Endpoints

Base URL: `http://localhost:8080/api`

### 🌋 Disasters
| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/disasters` | Get all disasters |
| `GET` | `/api/disasters/{id}` | Get disaster by ID |
| `POST` | `/api/disasters` | Create a new disaster |
| `PUT` | `/api/disasters/{id}` | Update a disaster |
| `DELETE` | `/api/disasters/{id}` | Delete a disaster |

### 🗺️ Zones
| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/zones` | Get all zones |
| `GET` | `/api/zones/{id}` | Get zone by ID |
| `POST` | `/api/zones` | Create a new zone |
| `PUT` | `/api/zones/{id}` | Update a zone |
| `DELETE` | `/api/zones/{id}` | Delete a zone |

### 🏠 Shelters
| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/shelters` | Get all shelters |
| `GET` | `/api/shelters/{id}` | Get shelter by ID |
| `POST` | `/api/shelters` | Add a new shelter |
| `PUT` | `/api/shelters/{id}` | Update shelter info |
| `DELETE` | `/api/shelters/{id}` | Remove a shelter |

### 🛣️ Routes
| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/routes` | Get all evacuation routes |
| `GET` | `/api/routes/{id}` | Get route by ID |
| `POST` | `/api/routes` | Create a new route |
| `PUT` | `/api/routes/{id}` | Update a route |
| `DELETE` | `/api/routes/{id}` | Delete a route |

### 👤 Users
| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/users` | Get all users |
| `GET` | `/api/users/{id}` | Get user by ID |
| `POST` | `/api/users` | Register a new user |
| `PUT` | `/api/users/{id}` | Update user details |
| `DELETE` | `/api/users/{id}` | Delete a user |

### 📋 Evacuation Requests
| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/requests` | Get all evacuation requests |
| `GET` | `/api/requests/{id}` | Get request by ID |
| `POST` | `/api/requests` | Submit a new evacuation request |
| `PUT` | `/api/requests/{id}` | Update request status |
| `DELETE` | `/api/requests/{id}` | Cancel a request |

---

## 🚀 How to Run the Project

### Prerequisites

Make sure you have the following installed:

- [Java 17+](https://adoptium.net/)
- [Maven 3.8+](https://maven.apache.org/)
- [MySQL 8+](https://www.mysql.com/) *(optional — H2 can be used for local development)*
- [Git](https://git-scm.com/)

---

### 1. Clone the Repository

```bash
git clone https://github.com/your-username/disaster-evacuation-engine.git
cd disaster-evacuation-engine
```

### 2. Configure the Database

**Option A — MySQL (Recommended for production):**

Open `src/main/resources/application.properties` and update:

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/evacuation_db
spring.datasource.username=your_mysql_username
spring.datasource.password=your_mysql_password
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
```

Create the database in MySQL:

```sql
CREATE DATABASE evacuation_db;
```

**Option B — H2 In-Memory Database (Quick start):**

```properties
spring.datasource.url=jdbc:h2:mem:evacuationdb
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.h2.console.enabled=true
spring.jpa.hibernate.ddl-auto=create-drop
```

Access the H2 console at: `http://localhost:8080/h2-console`

### 3. Build the Project

```bash
mvn clean install
```

### 4. Run the Application

```bash
mvn spring-boot:run
```

The application will start at: **`http://localhost:8080`**

> 📌 Sample data is automatically loaded at startup via `DataLoader.java` — no manual data entry required!

### 5. Test the APIs

Use [Postman](https://www.postman.com/) or [curl](https://curl.se/) to test the endpoints:

```bash
# Get all disasters
curl -X GET http://localhost:8080/api/disasters

# Create a new shelter
curl -X POST http://localhost:8080/api/shelters \
  -H "Content-Type: application/json" \
  -d '{"name": "City Hall Shelter", "location": "Main St", "capacity": 500, "available": true}'
```

---

## 🔮 Future Improvements

- [ ] 🔐 **Authentication & Authorization** — Implement JWT-based security with role-based access control (Admin, Responder, Citizen)
- [ ] 📍 **Geospatial Integration** — Add GPS coordinates to zones, shelters, and routes for map-based visualization
- [ ] 🧠 **Decision Algorithm** — Implement an intelligent route recommendation engine based on zone risk levels and shelter capacity
- [ ] 📊 **Dashboard API** — Aggregated statistics endpoints for real-time summary dashboards
- [ ] 🔔 **Notification System** — SMS/email alerts for users when evacuation requests are approved or routes are blocked
- [ ] 📦 **Docker Support** — Containerize the application with Docker and Docker Compose
- [ ] ☁️ **Cloud Deployment** — Deploy to AWS / Azure with CI/CD pipeline via GitHub Actions
- [ ] 🧪 **Unit & Integration Testing** — Comprehensive test coverage using JUnit 5 and Mockito
- [ ] 📝 **Swagger / OpenAPI Docs** — Auto-generated interactive API documentation

---

## 👨‍💻 Author

**Your Name**
- 🌐 GitHub: [@your-username](https://github.com/your-username)
- 💼 LinkedIn: [linkedin.com/in/your-profile](https://linkedin.com/in/your-profile)
- 📧 Email: your.email@example.com

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).

---

<div align="center">
  <i>Built with ❤️ to help communities stay safe during disasters.</i>
</div>
