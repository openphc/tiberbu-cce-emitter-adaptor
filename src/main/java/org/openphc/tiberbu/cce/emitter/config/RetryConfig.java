package org.openphc.tiberbu.cce.emitter.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Enables Spring Retry's {@code @Retryable}/{@code @Recover} annotation
 * processing — required for {@code CollectorForwardingService}'s
 * exponential-backoff retry on transient Collector failures.
 */
@Configuration
@EnableRetry
public class RetryConfig {
}
