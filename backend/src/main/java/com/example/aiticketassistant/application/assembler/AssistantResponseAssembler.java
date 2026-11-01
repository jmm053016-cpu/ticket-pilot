package com.example.aiticketassistant.application.assembler;

import com.example.aiticketassistant.application.workflow.WorkflowContext;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class AssistantResponseAssembler {
    public String assemble(WorkflowContext context, String orderAnalysis, String knowledgeAnswer) {
        String tools = context.toolResults().stream()
                .map(result -> "- " + result.tool() + ": " + (result.success() ? "成功" : "失败 - " + result.error()))
                .collect(Collectors.joining("\n"));
        String ticket = context.recall("ticket") == null ? "未创建新工单" : "已创建/更新工单：" + context.recall("ticket");
        Object orderConfirmation = context.recall("orderConfirmation");
        List<String> sections = new ArrayList<>();
        sections.add("我已完成多 Agent 分析。");
        sections.add("**意图识别**：%s（置信度 %.2f）".formatted(
                context.intent() == null ? "GENERAL_SUPPORT" : context.intent().intent(),
                context.intent() == null ? 0.0 : context.intent().confidence()));
        if (orderAnalysis != null && !orderAnalysis.isBlank()) {
            sections.add("**订单分析**：\n" + orderAnalysis);
        }
        if (knowledgeAnswer != null && !knowledgeAnswer.isBlank()) {
            sections.add("**知识库结论**：\n" + knowledgeAnswer);
        }
        if (orderConfirmation != null) {
            sections.add("**下单确认**：\n已生成待确认订单，请在页面确认商品、金额、地址和支付方式后再创建订单；系统不会自动扣款。");
        }
        sections.add("**业务动作**：\n" + ticket);
        sections.add("**工具执行 Trace**：\n" + (tools.isBlank() ? "- 未调用工具" : tools));
        return String.join("\n\n", sections) + "\n";
    }
}
