package com.example.aiticketassistant.application.tool.tools;

import com.example.aiticketassistant.application.ticket.TicketEventRecorder;
import com.example.aiticketassistant.application.ticket.TicketEventTypes;
import com.example.aiticketassistant.application.tool.ToolExecutor;
import com.example.aiticketassistant.application.tool.ToolMetadata;
import com.example.aiticketassistant.application.tool.ToolNames;
import com.example.aiticketassistant.application.tool.ToolResult;
import com.example.aiticketassistant.application.workflow.WorkflowContext;
import com.example.aiticketassistant.domain.shared.NotFoundException;
import com.example.aiticketassistant.domain.ticket.Ticket;
import com.example.aiticketassistant.domain.ticket.TicketId;
import com.example.aiticketassistant.domain.ticket.TicketRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class UpdateTicketTool implements ToolExecutor {
    private final TicketRepository ticketRepository;
    private final TicketEventRecorder ticketEventRecorder;

    public UpdateTicketTool(TicketRepository ticketRepository, TicketEventRecorder ticketEventRecorder) {
        this.ticketRepository = ticketRepository;
        this.ticketEventRecorder = ticketEventRecorder;
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                ToolNames.UPDATE_TICKET,
                "Update a ticket status or resolution through the business command layer.",
                Map.of("ticketId", "string", "action", "RESOLVE|ESCALATE|WAITING_CUSTOMER", "note", "string"),
                Map.of("ticketId", "string", "status", "string"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, WorkflowContext context) {
        String ticketId = string(arguments, "ticketId", null);
        if (ticketId == null) {
            Object created = context.recall("ticket");
            if (created instanceof Map<?, ?> map) {
                Object id = map.get("ticketId");
                ticketId = id == null ? null : id.toString();
            }
        }
        if (ticketId == null || ticketId.isBlank()) {
            return ToolResult.failure(ToolNames.UPDATE_TICKET, "ticketId is required");
        }
        String resolvedTicketId = ticketId;
        Ticket ticket = ticketRepository.findById(new TicketId(resolvedTicketId))
                .orElseThrow(() -> new NotFoundException("Ticket not found: " + resolvedTicketId));
        String action = string(arguments, "action", "WAITING_CUSTOMER").toUpperCase();
        String note = string(arguments, "note", "Updated by AI workflow");
        switch (action) {
            case "RESOLVE" -> ticket.resolve(note);
            case "ESCALATE" -> ticket.escalate(note);
            case "WAITING_CUSTOMER" -> ticket.markWaitingForCustomer();
            default -> ticket.applyAiSuggestion(note);
        }
        Ticket saved = ticketRepository.save(ticket);
        // 工单时间线：按动作生成事件（best-effort，写失败不影响工单本身）
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("action", action);
        event.put("note", note);
        event.put("status", saved.status().name());
        event.put("priority", saved.priority().name());
        event.put("source", "AI_TOOL");
        event.put("traceId", context.trace().traceId());
        event.put("sessionId", context.sessionId());
        ticketEventRecorder.record(saved.id().value(), eventTypeFor(action), event);
        Map<String, Object> data = Map.of("ticketId", saved.id().value(), "status", saved.status().name(), "priority", saved.priority().name());
        return ToolResult.success(ToolNames.UPDATE_TICKET, data);
    }

    private String eventTypeFor(String action) {
        return switch (action) {
            case "RESOLVE" -> TicketEventTypes.TICKET_RESOLVED;
            case "ESCALATE" -> TicketEventTypes.TICKET_ESCALATED;
            case "WAITING_CUSTOMER" -> TicketEventTypes.TICKET_WAITING_CUSTOMER;
            default -> TicketEventTypes.TICKET_UPDATED;
        };
    }

    private String string(Map<String, Object> args, String key, String fallback) {
        Object value = args == null ? null : args.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }
}
