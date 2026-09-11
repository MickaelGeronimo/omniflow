package com.omniflow.infrastructure.adapter.out.aws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniflow.application.port.out.SnsPublisherPort;
import io.awspring.cloud.sns.core.SnsTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class SnsEventPublisherAdapter implements SnsPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(SnsEventPublisherAdapter.class);

    private final SnsTemplate snsTemplate;
    private final ObjectMapper objectMapper;

    public SnsEventPublisherAdapter(@Autowired(required = false) @Nullable SnsTemplate snsTemplate, ObjectMapper objectMapper) {
        this.snsTemplate = snsTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(String topicName, String eventType, String correlationId, Map<String, Object> message) {
        try {
            String jsonPayload = objectMapper.writeValueAsString(message);
            Map<String, Object> headers = Map.of(
                    "eventType", eventType,
                    "correlationId", correlationId != null ? correlationId : "none"
            );

            if (snsTemplate != null) {
                snsTemplate.convertAndSend(topicName, jsonPayload, headers);
                log.info("[AWS-SNS] Published event [{}] for correlation [{}] to topic [{}]",
                        eventType, correlationId, topicName);
            } else {
                log.info("[AWS-SNS-SIMULATED] (No broker active) Event [{}] for correlation [{}] payload: {}",
                        eventType, correlationId, jsonPayload);
            }
        } catch (Exception e) {
            log.error("[AWS-SNS] Failed to publish message to topic [{}]", topicName, e);
            throw new RuntimeException("SNS publication failure", e);
        }
    }
}
