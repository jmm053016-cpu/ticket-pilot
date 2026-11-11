package com.example.aiticketassistant.application.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aiticketassistant.application.workflow.WorkflowContext;
import com.example.aiticketassistant.domain.catalog.Product;
import com.example.aiticketassistant.domain.catalog.ProductInventory;
import com.example.aiticketassistant.domain.catalog.ProductRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ToolDispatcherTest {

    /** 空目录：只要参数校验通过就会撞上"工具未注册"，足以区分两条失败路径。 */
    private final ProductRepository emptyCatalog = new ProductRepository() {
        @Override
        public Optional<Product> findById(String id) {
            return Optional.empty();
        }

        @Override
        public List<Product> findActiveProducts() {
            return List.of();
        }

        @Override
        public Optional<ProductInventory> findInventory(String productId) {
            return Optional.empty();
        }

        @Override
        public ProductInventory saveInventory(ProductInventory inventory) {
            return inventory;
        }
    };

    private ToolDispatcher dispatcher() {
        return new ToolDispatcher(new ToolRegistry(List.of()),
                (context, call, result, duration) -> {},
                new ToolArgumentGuard(emptyCatalog));
    }

    @Test
    void rejectsUnregisteredTools() {
        ToolResult result = dispatcher().dispatch(
                new ToolCall("UNKNOWN", Map.of()), new WorkflowContext("s", "c", null, "hello"));
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not AI-callable");
    }

    @Test
    void argumentGuardRunsBeforeExecution() {
        // 参数不合法（商品不存在）→ 在"工具未注册"之前就被拦住，报的是参数原因
        ToolResult result = dispatcher().dispatch(
                new ToolCall(ToolNames.CANCEL_ORDER, Map.of("orderNo", "不是订单号")),
                new WorkflowContext("s", "CUST-1001", null, "帮我取消订单"));
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("订单号格式不对");
    }
}
