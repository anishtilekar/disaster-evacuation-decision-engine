package com.evacuation.engine.security;

import com.evacuation.engine.service.GraphAdminService;
import com.evacuation.engine.service.HazardService;
import com.evacuation.engine.web.GraphAdminController;
import com.evacuation.engine.web.HazardController;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies {@link SecurityConfig}'s authorization matrix directly against real HTTP requests,
 * rather than trusting the matcher list to mean what it says. Two of the admin-only write endpoints
 * stand in for the rule: {@code POST /api/hazards} and {@code POST /api/graph/blocked-roads} are the
 * clearest cases where a USER reaching them would let an evacuee alter the routing world everyone
 * else is being planned against.
 *
 * <p>Authorization runs in the security filter chain, strictly before the controller's own
 * {@code @Valid} validation — so a USER is proven blocked by getting {@code 403} on a request that
 * would otherwise fail validation too, while an ADMIN is proven <em>past</em> the gate by getting
 * {@code 400} (validation failure) rather than {@code 403} on the exact same empty body. Neither
 * assertion needs a fully-valid request payload, only a controlled, deterministic outcome that
 * distinguishes "the gate stopped this" from "the gate let this through".
 */
@WebMvcTest(controllers = {HazardController.class, GraphAdminController.class})
@Import(SecurityConfig.class)
class AuthorizationMatrixTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HazardService hazardService;

    @MockBean
    private GraphAdminService graphAdminService;

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("A USER principal is forbidden from posting a hazard event")
    void userCannotPostHazard() throws Exception {
        mockMvc.perform(post("/api/hazards")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("A USER principal is forbidden from blocking a road")
    void userCannotBlockRoad() throws Exception {
        mockMvc.perform(post("/api/graph/blocked-roads")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("An anonymous caller gets a plain 401 on the API, not a redirect to an HTML login page")
    void anonymousGetsUnauthorizedOnApiEndpoint() throws Exception {
        mockMvc.perform(post("/api/hazards")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("An ADMIN principal passes authorization on POST /api/hazards, reaching validation instead of being blocked")
    void adminPassesAuthorizationOnHazardEndpoint() throws Exception {
        mockMvc.perform(post("/api/hazards")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("An ADMIN principal passes authorization on POST /api/graph/blocked-roads, reaching validation instead of being blocked")
    void adminPassesAuthorizationOnBlockRoadEndpoint() throws Exception {
        mockMvc.perform(post("/api/graph/blocked-roads")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("A missing CSRF token is rejected even for an otherwise-authorized ADMIN call")
    void missingCsrfTokenIsRejectedRegardlessOfRole() throws Exception {
        mockMvc.perform(post("/api/hazards")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
    }
}
