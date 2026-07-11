package com.evacuation.engine.model.enums;

public enum RoadStatus {

    OPEN,          // Road is available for evacuation routing

    BLOCKED,       // Road completely unavailable due to disaster/debris/etc.

    PARTIALLY_BLOCKED, // Limited access or reduced capacity

    UNDER_REPAIR,  // Road maintenance or restoration in progress

    CLOSED         // Officially closed by authorities
}