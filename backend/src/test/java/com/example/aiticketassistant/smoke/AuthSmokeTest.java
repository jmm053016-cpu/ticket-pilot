package com.example.aiticketassistant.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 鉴权冒烟（2026-11-09）：**这是唯一打开 {@code assistant.auth.enabled=true} 的测试**。
 *
 * <p>为什么单独一个类：鉴权默认在全站生效，但绝大多数用例关心业务，不想每条都登录；
 * 所以 {@code application-test.yml} 把它关掉，这里用内联属性单独打开，专门验证
 * 「没令牌 → 401」「有令牌 → 可用」「令牌里的客户说了算」「注销/越权 → 拒绝」。
 *
 * <p>与业务冒烟一样：H2 内存库 + 规则兜底 AI，不联网。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "assistant.ai.provider=fallback",
                "assistant.auth.enabled=true",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                "assistant.retrieval.embedding-provider=hash"
        })
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class AuthSmokeTest {

    private static final String OWNER = "CUST-1001";
    private static final String OTHER = "CUST-2002";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void seedCustomers() {
        jdbc.update("DELETE FROM auth_tokens");
        jdbc.update("DELETE FROM assistant_messages");
        jdbc.update("DELETE FROM assistant_sessions");
        jdbc.update("DELETE FROM customer_addresses");
        insertAddress(OWNER);
        insertAddress(OTHER);
        // 别人的会话：用来验证"知道 sessionId 也读不到"
        jdbc.update("""
                INSERT INTO assistant_sessions (id, customer_id, created_at, updated_at)
                VALUES ('other-session', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, OTHER);
    }

    private void insertAddress(String customerId) {
        jdbc.update("""
                INSERT INTO customer_addresses (id, customer_id, recipient_name, phone, address_line,
                                                is_default, active, created_at, updated_at)
                VALUES (?, ?, '测试用户', '138****0000', '测试地址', TRUE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, "ADDR-" + customerId, customerId);
    }

    private String login(String customerId) {
        String body = webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"customerId\":\"" + customerId + "\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        try {
            JsonNode node = objectMapper.readTree(body);
            return node.path("token").asText();
        } catch (Exception ex) {
            throw new IllegalStateException(body, ex);
        }
    }

    @Test
    void requestsWithoutTokenAreRejected() {
        webTestClient.get().uri("/assistant/sessions?customerId=" + OTHER)
                .exchange()
                .expectStatus().isUnauthorized();

        // 商品目录是公开的（首页要用）
        webTestClient.get().uri("/products").exchange().expectStatus().isOk();
    }

    @Test
    void loginIssuesUsableToken() {
        String token = login(OWNER);
        assertThat(token).startsWith("tk_");

        webTestClient.get().uri("/assistant/sessions?customerId=" + OWNER)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void tokenIdentityWinsOverParameter() {
        String token = login(OWNER);
        // 带着自己的令牌，但参数写别人的 customerId —— 必须按令牌的客户读（读不到别人的会话列表）
        String body = webTestClient.get().uri("/assistant/sessions?customerId=" + OTHER)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).as("不能因为参数写别人的 ID 就列出别人的会话").doesNotContain("other-session");
    }

    @Test
    void readingSomeoneElsesSessionIsNotFound() {
        String token = login(OWNER);
        webTestClient.get().uri("/assistant/sessions/other-session/messages?customerId=" + OWNER)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound();

        // 换成"本人"的令牌也一样：越权与不存在同一个 404
        String otherToken = login(OTHER);
        webTestClient.get().uri("/assistant/sessions/other-session/messages?customerId=" + OWNER)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                .exchange()
                .expectStatus().isOk();   // 自己的会话可以读（证明上面的 404 是归属校验，不是接口坏了）
    }

    @Test
    void revokedTokenStopsWorking() {
        String token = login(OWNER);
        webTestClient.post().uri("/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk();

        webTestClient.get().uri("/assistant/sessions?customerId=" + OWNER)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void unknownCustomerCannotLogin() {
        webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"customerId\":\"CUST-9999\"}")
                .exchange()
                .expectStatus().isBadRequest();
    }
}
