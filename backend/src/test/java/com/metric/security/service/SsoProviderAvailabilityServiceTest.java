package com.metric.security.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsoProviderAvailabilityServiceTest {
    @Test
    void unavailableProviderKeepsLocalLoginAvailable() {
        var service = new SsoProviderAvailabilityService(
                "http://127.0.0.1:7200/oauth/authorize", "metric-code", (host, port) -> false);
        assertFalse(service.isAvailable());
    }

    @Test
    void reachableProviderAllowsSsoRedirect() {
        var service = new SsoProviderAvailabilityService(
                "http://127.0.0.1:7200/oauth/authorize", "metric-code", (host, port) -> true);
        assertTrue(service.isAvailable());
    }

    @Test
    void placeholderClientDoesNotAllowSsoRedirectEvenWhenProviderPortIsReachable() {
        var service = new SsoProviderAvailabilityService(
                "http://127.0.0.1:7200/oauth/authorize", "placeholder-client-id", (host, port) -> true);
        assertFalse(service.isAvailable());
    }
}
