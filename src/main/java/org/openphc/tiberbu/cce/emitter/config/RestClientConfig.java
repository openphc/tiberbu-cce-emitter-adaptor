package org.openphc.tiberbu.cce.emitter.config;

import org.openphc.tiberbu.cce.emitter.service.CollectorTokenService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Configures the {@link RestClient} bean used to forward CloudEvents to the
 * CCE Collector.
 *
 * <p>A fresh {@link RestClient} is safe to share as a singleton (unlike HAPI's
 * {@code IParser}) — it holds no per-request mutable state.
 */
@Configuration
public class RestClientConfig {

    /**
     * Builds the Collector-bound {@link RestClient}.
     *
     * <p>Configured with: base URL from {@link CollectorProperties#url()};
     * connect and read timeout, both from {@link CollectorProperties#timeout()};
     * a default {@code Content-Type: application/json} header; and a request
     * interceptor that attaches {@code Authorization: Bearer <token>} only
     * when {@link CollectorTokenService#getToken()} returns a non-null,
     * non-blank value — when neither auth mode is configured, no
     * {@code Authorization} header is sent at all.
     *
     * @param collectorProperties Collector target + timeout configuration
     * @param collectorTokenService resolves the Bearer token per request (OAuth2, static, or none)
     * @return the configured {@link RestClient}, qualified {@code "collectorRestClient"}
     */
    @Bean
    @Qualifier("collectorRestClient")
    public RestClient collectorRestClient(
            CollectorProperties collectorProperties, CollectorTokenService collectorTokenService) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(collectorProperties.timeout()));
        requestFactory.setReadTimeout(Duration.ofMillis(collectorProperties.timeout()));

        ClientHttpRequestInterceptor bearerTokenInterceptor = (request, body, execution) -> {
            String token = collectorTokenService.getToken();
            if (token != null && !token.isBlank()) {
                request.getHeaders().setBearerAuth(token);
            }
            return execution.execute(request, body);
        };

        return RestClient.builder()
                .baseUrl(collectorProperties.url())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory)
                .requestInterceptor(bearerTokenInterceptor)
                .build();
    }
}
