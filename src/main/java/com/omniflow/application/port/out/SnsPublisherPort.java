package com.omniflow.application.port.out;

import java.util.Map;

public interface SnsPublisherPort {

    void publish(String topicName, String eventType, String correlationId, Map<String, Object> message);
}
