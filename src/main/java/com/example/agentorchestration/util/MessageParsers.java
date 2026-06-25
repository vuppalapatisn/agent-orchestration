package com.example.agentorchestration.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MessageParsers {

    private static final Pattern SERVICE_PATTERN = Pattern.compile("([a-zA-Z0-9-]+(?:service|svc|api))", Pattern.CASE_INSENSITIVE);

    private MessageParsers() {
    }

    public static String extractServiceName(String message) {
        if (message == null || message.isBlank()) {
            return "application-service";
        }
        Matcher matcher = SERVICE_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1) : "application-service";
    }
}
