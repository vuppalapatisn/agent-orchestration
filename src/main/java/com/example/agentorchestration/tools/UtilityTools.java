package com.example.agentorchestration.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;

@Service
public class UtilityTools {

    @Tool(description = "Get the current system date and time")
    public String now() {
        return ZonedDateTime.now().toString();
    }
}
