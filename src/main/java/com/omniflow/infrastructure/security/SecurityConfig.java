package com.omniflow.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final CorrelationIdFilter correlationIdFilter;
    private final RateLimitingFilter rateLimitingFilter;
    private final ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;

    public SecurityConfig(
            CorrelationIdFilter correlationIdFilter,
            RateLimitingFilter rateLimitingFilter,
            ApiKeyAuthenticationFilter apiKeyAuthenticationFilter) {
        this.correlationIdFilter = correlationIdFilter;
        this.rateLimitingFilter = rateLimitingFilter;
        this.apiKeyAuthenticationFilter = apiKeyAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public Kubernetes liveness and readiness health probes
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()

                        // Operational metrics restricted to monitoring / admin roles
                        .requestMatchers("/actuator/**").hasAnyRole("OPS", "ADMIN")

                        // Financial transactions submission
                        .requestMatchers(HttpMethod.POST, "/api/v1/transactions")
                        .hasAnyRole("CLIENT", "OPERATOR", "ADMIN")

                        // Nightly batch reconciliation execution
                        .requestMatchers(HttpMethod.POST, "/api/v1/reconciliation/**")
                        .hasAnyRole("OPERATIONS", "ADMIN")

                        // AI Incident Triage (Preview)
                        .requestMatchers(HttpMethod.POST, "/api/v1/ai/**")
                        .hasAnyRole("AUDITOR", "RISK_ENGINEER", "ADMIN")

                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
                .addFilterBefore(correlationIdFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rateLimitingFilter, CorrelationIdFilter.class)
                .addFilterAfter(apiKeyAuthenticationFilter, RateLimitingFilter.class);

        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder(@Value("${omniflow.security.jwt-secret}") String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        SecretKey key = new SecretKeySpec(keyBytes, "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).build();
    }
}
