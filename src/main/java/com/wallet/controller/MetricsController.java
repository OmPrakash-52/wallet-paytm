package com.wallet.controller;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The exercise spec names the literal path "/metrics". Spring Boot Actuator's
 * own convention is /actuator/prometheus (kept as-is, since some tooling
 * expects that path) - this is a thin alias exposing the identical
 * Prometheus-format scrape at /metrics directly.
 */
@RestController
public class MetricsController {

    private final PrometheusMeterRegistry prometheusMeterRegistry;

    public MetricsController(PrometheusMeterRegistry prometheusMeterRegistry) {
        this.prometheusMeterRegistry = prometheusMeterRegistry;
    }

    @GetMapping(value = "/metrics", produces = MediaType.TEXT_PLAIN_VALUE)
    public String metrics() {
        return prometheusMeterRegistry.scrape();
    }
}
