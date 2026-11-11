package com.example.aiticketassistant.interfaces.sse;

import com.example.aiticketassistant.application.assistant.AssistantRequest;
import com.example.aiticketassistant.application.assistant.AssistantWorkflowService;
import com.example.aiticketassistant.infrastructure.web.AuthContext;
import com.example.aiticketassistant.interfaces.dto.AssistantSseEvent;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/assistant")
public class AssistantStreamController {
    private final AssistantWorkflowService workflowService;

    public AssistantStreamController(AssistantWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<AssistantSseEvent>>> stream(
            @RequestParam String message,
            @RequestParam(defaultValue = "CUST-1001") String customerId,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String sessionId,
            ServerWebExchange exchange) {
        if (message == null || message.isBlank()) {
            throw new com.example.aiticketassistant.domain.shared.DomainException("message 不能为空");
        }
        // 令牌优先：有令牌时以令牌里的客户为准，参数里的 customerId 不再具备授权含义（§5.7）
        AssistantRequest request = new AssistantRequest(
                sessionId == null || sessionId.isBlank() ? "session-" + UUID.randomUUID() : sessionId,
                AuthContext.resolveCustomer(exchange, customerId),
                orderNo,
                message);
        Flux<ServerSentEvent<AssistantSseEvent>> stream = workflowService.stream(request)
                .map(event -> ServerSentEvent.<AssistantSseEvent>builder()
                        .event(event.type().wireName())
                        .data(new AssistantSseEvent(event.type().wireName(), event.message(), event.payload(), event.timestamp()))
                        .build());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .header(HttpHeaders.CONNECTION, "keep-alive")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(stream);
    }
}
