package com.example.aiticketassistant.application.tool.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.aiticketassistant.application.ticket.TicketEventRecorder;
import com.example.aiticketassistant.application.ticket.TicketEventTypes;
import com.example.aiticketassistant.application.tool.ToolResult;
import com.example.aiticketassistant.application.workflow.WorkflowContext;
import com.example.aiticketassistant.domain.ticket.Ticket;
import com.example.aiticketassistant.domain.ticket.TicketCategory;
import com.example.aiticketassistant.domain.ticket.TicketId;
import com.example.aiticketassistant.domain.ticket.TicketPriority;
import com.example.aiticketassistant.domain.ticket.TicketRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 工单事件时间线的契约测试：两个工单工具落库后必须追加一条正确类型的事件。
 *
 * <p>UPDATE_TICKET 在 fallback provider 下无法通过 HTTP 触发（兜底规则不产出该工具调用），
 * 所以这里直接单测工具层，保证事件类型映射不会退化。
 */
class TicketEventEmissionTest {
    private final TicketRepository ticketRepository = mock(TicketRepository.class);
    private final List<RecordedEvent> events = new ArrayList<>();
    private final TicketEventRecorder recorder = (ticketId, eventType, payload) ->
            events.add(new RecordedEvent(ticketId, eventType, payload));
    private final WorkflowContext context = new WorkflowContext("session-1", "CUST-1001", null, "帮我创建一个支付失败工单");

    @Test
    void createTicketEmitsTicketCreatedEvent() {
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ToolResult result = new CreateTicketTool(ticketRepository, recorder)
                .execute(Map.of("title", "支付失败", "priority", "HIGH"), context);

        assertThat(result.success()).isTrue();
        assertThat(events).hasSize(1);
        RecordedEvent event = events.get(0);
        assertThat(event.eventType()).isEqualTo(TicketEventTypes.TICKET_CREATED);
        assertThat(event.ticketId()).isEqualTo(((Map<?, ?>) result.data()).get("ticketId"));
        assertThat(event.payload())
                .containsEntry("status", "OPEN")
                .containsEntry("priority", "HIGH")
                .containsEntry("source", "AI_TOOL")
                .containsEntry("traceId", context.trace().traceId())
                .containsEntry("sessionId", "session-1");
    }

    @Test
    void resolveEmitsTicketResolvedEvent() {
        RecordedEvent event = updateTicket("RESOLVE");

        assertThat(event.eventType()).isEqualTo(TicketEventTypes.TICKET_RESOLVED);
        assertThat(event.payload()).containsEntry("status", "RESOLVED").containsEntry("action", "RESOLVE");
    }

    @Test
    void escalateEmitsTicketEscalatedEvent() {
        RecordedEvent event = updateTicket("ESCALATE");

        assertThat(event.eventType()).isEqualTo(TicketEventTypes.TICKET_ESCALATED);
        assertThat(event.payload()).containsEntry("priority", "URGENT");
    }

    @Test
    void waitingCustomerEmitsWaitingEvent() {
        RecordedEvent event = updateTicket("WAITING_CUSTOMER");

        assertThat(event.eventType()).isEqualTo(TicketEventTypes.TICKET_WAITING_CUSTOMER);
        assertThat(event.payload()).containsEntry("status", "WAITING_CUSTOMER");
    }

    @Test
    void unknownActionEmitsGenericUpdatedEvent() {
        RecordedEvent event = updateTicket("SUGGEST");

        assertThat(event.eventType()).isEqualTo(TicketEventTypes.TICKET_UPDATED);
    }

    private RecordedEvent updateTicket(String action) {
        Ticket existing = Ticket.restore(
                new TicketId("TKT-TEST-001"), "CUST-1001", "支付失败", "支付失败但订单未生成",
                TicketCategory.PAYMENT, TicketPriority.MEDIUM,
                com.example.aiticketassistant.domain.ticket.TicketStatus.OPEN, null, null, null, null);
        when(ticketRepository.findById(any(TicketId.class))).thenReturn(Optional.of(existing));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ToolResult result = new UpdateTicketTool(ticketRepository, recorder)
                .execute(Map.of("ticketId", "TKT-TEST-001", "action", action, "note", "AI 处理备注"), context);

        assertThat(result.success()).isTrue();
        assertThat(events).hasSize(1);
        return events.get(0);
    }

    private record RecordedEvent(String ticketId, String eventType, Map<String, Object> payload) {}
}
