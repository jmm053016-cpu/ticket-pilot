package com.example.aiticketassistant.application.ticket;

/**
 * 工单事件类型常量，原样写入 {@code ticket_events.event_type}（VARCHAR(64)）。
 *
 * <p>命名约定 {@code TICKET_<动作>}，便于按前缀查询某张工单的完整时间线。
 */
public final class TicketEventTypes {
    public static final String TICKET_CREATED = "TICKET_CREATED";
    public static final String TICKET_UPDATED = "TICKET_UPDATED";
    public static final String TICKET_RESOLVED = "TICKET_RESOLVED";
    public static final String TICKET_ESCALATED = "TICKET_ESCALATED";
    public static final String TICKET_WAITING_CUSTOMER = "TICKET_WAITING_CUSTOMER";

    private TicketEventTypes() {}
}
