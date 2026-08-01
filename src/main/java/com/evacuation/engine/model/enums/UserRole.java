package com.evacuation.engine.model.enums;

/** The two roles the app's Spring Security layer grants: USER (submit + track own requests) and ADMIN (everything else). */
public enum UserRole {
    USER,
    ADMIN
}
