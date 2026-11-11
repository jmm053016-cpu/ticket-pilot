package com.example.aiticketassistant.application.ticket;

import java.util.Map;

/**
 * 工单事件落库端口：每次工单状态变化追加一行，形成不可变的时间线。
 *
 * <p>工单聚合本身只保存"当前快照"，历史靠这张表回答"什么时候被谁改成什么样"。
 * 实现方必须自行兜住异常——事件缺失不应让工单创建/更新失败。
 */
public interface TicketEventRecorder {
    /**
     * @param ticketId  工单 ID
     * @param eventType  见 {@link TicketEventTypes}
     * @param payload    事件附加信息（动作、备注、变更后的状态/优先级等）
     */
    void record(String ticketId, String eventType, Map<String, Object> payload);
}
