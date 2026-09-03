package org.openphc.tiberbu.cce.emitter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the tibERbu CCE Emitter Adaptor.
 *
 * <p>The adaptor receives Bundle-based clinical event payloads from tibERbu on
 * {@code POST /inbound}, skips the patient entry at {@code resource.entry[0]},
 * normalizes every subsequent entry into its own CloudEvents v1.0 envelope, and
 * forwards each one to the CCE Collector.
 *
 * <p>The service is stateless — it holds no database, no broker and no session
 * state. All context is derived from the inbound request.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CceEmitterAdaptorApplication {

    public static void main(String[] args) {
        SpringApplication.run(CceEmitterAdaptorApplication.class, args);
    }
}
