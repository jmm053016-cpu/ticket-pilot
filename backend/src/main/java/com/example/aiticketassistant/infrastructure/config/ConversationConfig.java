package com.example.aiticketassistant.infrastructure.config;

import com.example.aiticketassistant.application.conversation.ConversationHistory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@code assistant.conversation.*} 映射成应用层的 {@link ConversationHistory.Budget}。
 *
 * <p>为什么不直接把 {@code AssistantProperties} 注入应用层：那是基础设施层的配置对象，
 * 应用层只应该看到"预算是多少"这件事（一个值对象），不该知道配置是怎么绑定的。
 * 配错/缺省一律由 {@code Budget} 自己回落到默认值（见其 compact constructor）。
 */
@Configuration
public class ConversationConfig {

    @Bean
    ConversationHistory.Budget conversationHistoryBudget(AssistantProperties properties) {
        AssistantProperties.Conversation conversation = properties.conversation();
        if (conversation == null) {
            return ConversationHistory.Budget.defaults();
        }
        return new ConversationHistory.Budget(
                conversation.maxTurns(),
                conversation.maxPromptChars(),
                conversation.maxUserChars(),
                conversation.maxAnswerChars());
    }
}
