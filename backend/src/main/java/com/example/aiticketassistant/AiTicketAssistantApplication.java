package com.example.aiticketassistant;

import com.example.aiticketassistant.infrastructure.config.AssistantProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(AssistantProperties.class)
@EnableScheduling
public class AiTicketAssistantApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiTicketAssistantApplication.class, args);
    }
}
