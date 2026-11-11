package com.example.aiticketassistant.application.tool.tools;

import com.example.aiticketassistant.application.ticket.TicketEventRecorder;
import com.example.aiticketassistant.application.ticket.TicketEventTypes;
import com.example.aiticketassistant.application.tool.ToolExecutor;
import com.example.aiticketassistant.application.tool.ToolMetadata;
import com.example.aiticketassistant.application.tool.ToolNames;
import com.example.aiticketassistant.application.tool.ToolResult;
import com.example.aiticketassistant.application.workflow.WorkflowContext;
import com.example.aiticketassistant.domain.ticket.Ticket;
import com.example.aiticketassistant.domain.ticket.TicketCategory;
import com.example.aiticketassistant.domain.ticket.TicketPriority;
import com.example.aiticketassistant.domain.ticket.TicketRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CreateTicketTool implements ToolExecutor {
    private final TicketRepository ticketRepository;
    private final TicketEventRecorder ticketEventRecorder;

    public CreateTicketTool(TicketRepository ticketRepository, TicketEventRecorder ticketEventRecorder) {
        this.ticketRepository = ticketRepository;
        this.ticketEventRecorder = ticketEventRecorder;
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                ToolNames.CREATE_TICKET,
                "Create a support ticket through the business command layer.",
                Map.of("required", "customerId,title,description,priority"),
                Map.of("ticketId", "string", "status", "string"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, WorkflowContext context) {
        String customerId = string(arguments, "customerId", context.customerId());
        String title = string(arguments, "title", "AI Support Ticket");
        String description = string(arguments, "description", context.userMessage());
        TicketPriority priority = TicketPriority.normalize(string(arguments, "priority", "MEDIUM"));
        TicketCategory category = TicketCategory.fromIntent(context.intent() == null ? title : context.intent().intent().name());
        Ticket ticket = Ticket.open(customerId, title, description, category, priority);
        Ticket saved = ticketRepository.save(ticket);
        Map<String, Object> data = Map.of(
                "ticketId", saved.id().value(),
                "title", saved.title(),
                "status", saved.status().name(),
                "priority", saved.priority().name(),
                "category", saved.category().name());
        // 工单时间线：创建事件（best-effort，写失败不影响工单本身）
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("action", "CREATE");
        event.put("title", saved.title());
        event.put("status", saved.status().name());
        event.put("priority", saved.priority().name());
        event.put("category", saved.category().name());
        event.put("customerId", saved.customerId());
        event.put("source", "AI_TOOL");
        event.put("traceId", context.trace().traceId());
        event.put("sessionId", context.sessionId());
        ticketEventRecorder.record(saved.id().value(), TicketEventTypes.TICKET_CREATED, event);
        context.remember("ticket", data);
        return ToolResult.success(ToolNames.CREATE_TICKET, data);
    }

    private String string(Map<String, Object> args, String key, String fallback) {
        Object value = args == null ? null : args.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }
}
