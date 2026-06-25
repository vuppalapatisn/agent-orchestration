package com.example.agentorchestration.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessageParsersTests {

    @Test
    void shouldExtractServiceName() {
        assertEquals("payment-service", MessageParsers.extractServiceName("Restart payment-service after health check"));
    }
}
