package com.example.aiticketassistant.infrastructure.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.aiticketassistant.application.prompt.PromptIds;
import com.example.aiticketassistant.application.prompt.PromptTemplate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 提示词外置后的契约测试：文件存在性、变量名匹配、渲染严格性、JSON 花括号不被误伤。
 */
class ClasspathPromptCatalogTest {
    private final ClasspathPromptCatalog catalog = new ClasspathPromptCatalog();

    @Test
    void loadsEveryDeclaredPromptWithSystemAndUserTemplates() {
        for (String id : PromptIds.ALL) {
            PromptTemplate template = catalog.get(id);

            assertThat(template.id()).as("prompt %s", id).isEqualTo(id);
            assertThat(template.system()).as("prompt %s system", id).isNotBlank();
            assertThat(template.userTemplate()).as("prompt %s user", id).isNotBlank().contains("{{");
        }
    }

    @Test
    void rendersEveryTemplateWithItsCallSiteVariables() {
        for (String id : PromptIds.ALL) {
            String rendered = catalog.get(id).user(variablesFor(id));

            assertThat(rendered).as("prompt %s 渲染结果", id).doesNotContain("{{");
        }
    }

    @Test
    void intentAgentSystemKeepsStrictJsonAndToolContract() {
        String system = catalog.get(PromptIds.INTENT_AGENT).system();

        // 2026-11-11：提示词不再硬编码工具枚举（工具清单由 ToolRegistry 运行时注入），
        // 但"严格 JSON + 必须走工具清单 + 关键选择口径"这三条契约必须保留。
        assertThat(system)
                .contains("Return strict JSON only")
                .contains("\"tool_calls\"")
                .contains("Tool metadata")
                .contains("ADD_TO_CART")
                .contains("VIEW_CART")
                .contains("Cart first")
                .contains("Never invent productId");
    }

    @Test
    void intentAgentRendersUserMessageCustomerOrderNoAndToolMetadata() {
        String rendered = catalog.get(PromptIds.INTENT_AGENT).user(variablesFor(PromptIds.INTENT_AGENT));

        assertThat(rendered)
                .contains("User message: 价格刚好699的衣服是什么")
                .contains("CustomerId: CUST-1001")
                .contains("OrderNo: null")
                .contains("Tool metadata: - QUERY_ORDER");
    }

    @Test
    void knowledgeAgentRendersRetrievedSources() {
        String rendered = catalog.get(PromptIds.KNOWLEDGE_AGENT).user(variablesFor(PromptIds.KNOWLEDGE_AGENT));

        assertThat(rendered)
                .contains("User question: 退款怎么办")
                .contains("Retrieved sources:\n- [退款政策] faq.md");
    }

    @Test
    void directAnswerSystemKeepsNoMutationGuardrails() {
        assertThat(catalog.get(PromptIds.DIRECT_ANSWER).system())
                .contains("不要声称已经查询用户订单")
                .contains("如果用户要求真实业务动作");
    }

    @Test
    void directAnswerTellsTheModelHowToTreatRetrievedKnowledge() {
        // 直答路径现在会带知识库资料：提示词必须同时说明"有资料要按资料答并标依据"和"没资料按常识但别编"
        String system = catalog.get(PromptIds.DIRECT_ANSWER).system();

        assertThat(system)
                .contains("Retrieved knowledge")
                .contains("**依据**")
                .contains("**为空**时")   // == 无资料时的行为
                .contains("不要编造引用");
        assertThat(catalog.get(PromptIds.DIRECT_ANSWER).userTemplate()).contains("{{sources}}");
    }

    @Test
    void rejectsUnresolvedPlaceholderInsteadOfSendingHalfPrompt() {
        PromptTemplate template = new PromptTemplate("demo", "system", "Hello {{missing}}");

        assertThatThrownBy(() -> template.user(Map.of("userMessage", "hi")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("{{missing}}");
    }

    @Test
    void rejectsUnknownPromptId() {
        assertThatThrownBy(() -> catalog.get("not-a-prompt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not-a-prompt");
    }

    @Test
    void keepsJsonBracesAndPercentSignsUntouched() {
        PromptTemplate template = new PromptTemplate("demo", "system",
                "{\"tool_calls\":[{\"tool\":\"QUERY_ORDER\",\"arguments\":{}}]} {{userMessage}} 100% 完成");

        assertThat(template.user(Map.of("userMessage", "hi")))
                .isEqualTo("{\"tool_calls\":[{\"tool\":\"QUERY_ORDER\",\"arguments\":{}}]} hi 100% 完成");
    }

    @Test
    void allowsTemplateWithoutPlaceholders() {
        PromptTemplate template = new PromptTemplate("demo", "system", "no placeholder here");

        assertThat(template.user(Map.of())).isEqualTo("no placeholder here");
    }

    private static Map<String, Object> variablesFor(String id) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("history", "- 无历史 -");
        if (PromptIds.INTENT_AGENT.equals(id)) {
            variables.put("userMessage", "价格刚好699的衣服是什么");
            variables.put("customerId", "CUST-1001");
            variables.put("orderNo", null);
            variables.put("toolMetadata", "- QUERY_ORDER");
        }
        if (PromptIds.ORDER_AGENT.equals(id)) {
            variables.put("userMessage", "我要买一件黑色卫衣");
            variables.put("orderToolResult", "{\"orderNo\":\"ORD-1001\"}");
        }
        if (PromptIds.KNOWLEDGE_AGENT.equals(id)) {
            variables.put("userMessage", "退款怎么办");
            variables.put("sources", "- [退款政策] faq.md (score 0.80): 7 天内可退");
            variables.put("planResult", "预算 ¥1000.00；每件商品不能重复`n最多可买 5 件，合计 ¥885.00，剩余 ¥115.00");
        }
        if (PromptIds.DIRECT_ANSWER.equals(id)) {
            variables.put("userMessage", "我有1000元最多可以买几件衣服");
            variables.put("sources", "- 退款政策：7 天内可退");
        }
        return variables;
    }
}
