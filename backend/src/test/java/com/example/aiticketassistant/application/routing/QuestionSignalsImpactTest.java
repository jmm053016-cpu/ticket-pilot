package com.example.aiticketassistant.application.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * `QuestionSignals` 的**影响面矩阵**（原 §11.1 第 5 条）。
 *
 * <p>问题背景：一张关键词表被九处复用（路由 / 意图启发式 / 意图护栏 / 兜底 / 检索加权 / 两个工具），
 * 改一个词会同时影响所有路。以前唯一的防线是"跑全量测试"—— 挂了才知道伤到谁，而且看不出**哪一路**变了。
 *
 * <p>本类把这件事变成"可视化 + 可审批"：
 * <ol>
 *   <li><b>登记对账</b>：`QuestionSignals` 新增 public 信号方法时必须在 {@code signalConsumers()} 里写清消费者，
 *       否则 {@link #everyPublicSignalDeclaresItsConsumers()} 失败 —— 逼着人先想"这会被谁用"；</li>
 *   <li><b>意图断言</b>：每张表的每个词条都必须点亮它该点亮的信号（{@link #everyCueLightsItsSignal()}），
 *       防"加了个词但它其实不生效"的哑弹；</li>
 *   <li><b>快照矩阵</b>：把「每条语料 → 所有信号」写进 {@code src/test/resources/question-signals-impact.txt}。
 *       改表导致的任何语义变化都会在这里给出精确 diff（哪条语料、哪个信号从 false 变 true），
 *       review 时一眼看出波及面。</li>
 * </ol>
 *
 * <p>**改完表之后**：先读测试打印的 diff，确认每条变化都是你想要的影响面，然后
 * {@code mvn -DsignalsSnapshot=true -Dtest=QuestionSignalsImpactTest test} 重新生成快照，
 * 把快照 diff 一起提交。
 */
class QuestionSignalsImpactTest {
    /** 快照文件（相对模块根：`mvn test` 与 IDEA 的运行目录都是 backend/）。 */
    private static final Path SNAPSHOT = Path.of("src/test/resources/question-signals-impact.txt");
    /** 置 true 重新生成快照（生成后**必须人工 review diff**）。 */
    private static final String REGENERATE_FLAG = "signalsSnapshot";

    /** 真实问法 + 已知反例：快照里最有价值的部分（表里的裸词看不出组合效果）。 */
    private static final List<String> REAL_QUESTIONS = List.of(
            "价格刚好699的衣服是什么",
            "比699便宜的衣服有哪些",
            "1000元预算可以怎么搭配",
            "我有1000元，最多可以买几件衣服",
            "我有1000元，最多可以买几件衣服，每件不能重复",
            "我要买一件黑色连帽卫衣",
            "帮我下单 2 件基础纯棉白色T恤",
            "我想要买米色帆布双肩包和藏青帆布鞋，帮我下单",
            "我想买个暖和的围脖",
            // 2026-11-09 新上线的问法：购物车规则问句（不得被当成查看命令）、退款进度查询
            "购物车里的东西会自动清空吗",
            "我的退款到哪了 ORD-AI-BEC2138C",
            "我要退货 ORD-AI-BEC2138C",
            "我的退货到哪了",
            "退货流程是什么",
            "我的订单有哪些",
            "订单 ORD-1001 到哪了",
            "取消订单",
            "我的收货地址能改吗",
            "创建支付失败工单",
            "更新工单状态",
            // —— 已知反例：这些都不该被当成商品问题或下单动作 ——
            "你好",
            "谢谢，明白了",
            "推荐一个退款流程",
            "支付失败怎么办",
            "订单显示 FAILED 是什么意思",
            "帮我写一首诗");

    @Test
    void everyPublicSignalDeclaresItsConsumers() {
        Set<String> declared = QuestionSignals.signalConsumers().keySet();
        Set<String> actual = publicSignalNames();

        assertThat(actual)
                .as("QuestionSignals 的 public 信号必须与 signalConsumers() 一一对应（新增信号请登记消费者）")
                .containsExactlyInAnyOrderElementsOf(declared);
        assertThat(QuestionSignals.signalConsumers().values())
                .as("每个信号至少要说得出一个消费者")
                .allSatisfy(consumers -> assertThat(consumers).isNotEmpty());
    }

    @Test
    void everyCueLightsItsSignal() {
        Map<String, List<String>> tables = QuestionSignals.cueTables();

        // 品类词：单独出现就该被认出来（否则 catalogQueryFor 不会补领域词）
        for (String cue : tables.get("CATEGORY")) {
            assertThat(QuestionSignals.hasCategoryWord(cue)).as("品类词「%s」", cue).isTrue();
        }
        // 检索线索 + 品类词 → 商品/预算问题
        for (String cue : tables.get("CATALOG_CUES")) {
            assertThat(QuestionSignals.isCatalogOrBudgetQuestion("衣服" + cue))
                    .as("「衣服 + %s」应判为商品问题", cue).isTrue();
        }
        // 明确的动作类表：既要能识别，也要能路由进工作流
        assertCues(tables.get("PURCHASE_CUES"), QuestionSignals::hasPurchaseIntent, "hasPurchaseIntent");
        assertCues(tables.get("CART_CUES"), QuestionSignals::isCartAction, "isCartAction");
        assertCues(tables.get("ORDER_LIST_CUES"), QuestionSignals::isOrderListRequest, "isOrderListRequest");
        assertCues(tables.get("ACCOUNT_CUES"), QuestionSignals::isAccountQuery, "isAccountQuery");
        assertCues(tables.get("ORDER_LIFECYCLE_CUES"), QuestionSignals::isOrderLifecycleAction, "isOrderLifecycleAction");
        assertCues(tables.get("TICKET_ACTION_CUES"), QuestionSignals::isTicketActionRequest, "isTicketActionRequest");
        // 汇总线索 → 检索侧要能给整张价格表加权
        for (String cue : tables.get("AGGREGATE_CUES")) {
            assertThat(QuestionSignals.isAggregateQuestion(cue)).as("汇总线索「%s」", cue).isTrue();
        }
        // 商品名表：每个名字都要能解析出 productId（否则「我要买 XX」下不了单）
        for (String name : tables.get("PRODUCT_NAME_TO_ID")) {
            assertThat(QuestionSignals.purchaseProductId("我要买一件" + name))
                    .as("商品名「%s」应解析出 productId", name).isNotNull();
        }
    }

    @Test
    void routingIsExactlyTheOrOfItsNineSignals() {
        // 组合搬家（2026-11-05）后仍要保证"路由 == 九路 OR"，防止有人只改一边。
        // 第三路是「购买意图**且能定位到商品**」：只有"买"没有商品时走直答（见 needsAgentWorkflow 注释）。
        for (String message : allCorpus()) {
            boolean expected = QuestionSignals.hasOrderNo(message)
                    || QuestionSignals.isCatalogOrBudgetQuestion(message)
                    || QuestionSignals.purchaseProductId(message) != null
                    || QuestionSignals.isCartAction(message)
                    || QuestionSignals.isAddToCartRequest(message)
                    || QuestionSignals.isViewCartRequest(message)
                    || QuestionSignals.isCartCheckoutRequest(message)
                    || QuestionSignals.isCancelOrderRequest(message)
                    || QuestionSignals.isRefundRequest(message)
                    || QuestionSignals.isOrderListRequest(message)
                    || QuestionSignals.isAccountQuery(message)
                    || QuestionSignals.isOrderLifecycleAction(message)
                    || QuestionSignals.isTicketCreateRequest(message)
                    || QuestionSignals.isTicketActionRequest(message)
                    // 2026-11-09：购物车三态、退款/退货的动作与进度查询也各自是路由条件
                    || QuestionSignals.isRefundStatusQuery(message)
                    || QuestionSignals.isReturnRequest(message)
                    || QuestionSignals.isReturnStatusQuery(message);
            assertThat(QuestionSignals.needsAgentWorkflow(message))
                    .as("「%s」的路由判定与九路 OR 不一致", message)
                    .isEqualTo(expected);
        }
    }

    @Test
    void impactMatrixMatchesTheCommittedSnapshot() throws Exception {
        String actual = renderMatrix();
        if (Boolean.parseBoolean(System.getProperty(REGENERATE_FLAG, "false")) || !Files.exists(SNAPSHOT)) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, actual, StandardCharsets.UTF_8);
            System.out.println("[QuestionSignals] 已重新生成影响面快照：" + SNAPSHOT.toAbsolutePath()
                    + "（请人工 review diff 后提交）");
            return;
        }
        List<String> diffs = diff(parseSnapshot(Files.readAllLines(SNAPSHOT, StandardCharsets.UTF_8)),
                parseSnapshot(actual.lines().toList()));
        // 只报差异条目，不整份对比 —— 否则 AssertJ 会把 250 行全打出来，跟"看不出波及面"没区别
        String hint = diffs.size() > 20 ? "（共 %d 处差异，只显示前 20 处）".formatted(diffs.size()) : "";
        assertThat(diffs.stream().limit(20).toList())
                .as("""
                        QuestionSignals 的语义变了 —— 下面每一处都会影响某条链路。
                        逐条确认都是你想要的影响面（信号 → 消费者见 QuestionSignals.signalConsumers() 与 §5.4.2），然后执行
                          mvn -D%s=true -Dtest=QuestionSignalsImpactTest test
                        重新生成快照，并把快照 diff 一起提交。%s""".formatted(REGENERATE_FLAG, hint))
                .isEmpty();
    }

    // ===================== 快照对比 =====================

    /** 快照 → 「语料标签 → 信号摘要」。按标签比对，表里插一个词就只有一处变化，不会被行号位移干扰。 */
    private static Map<String, String> parseSnapshot(List<String> lines) {
        Map<String, String> parsed = new TreeMap<>();
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int arrow = line.indexOf(" => ");
            if (arrow > 0) {
                parsed.put(line.substring(0, arrow), line.substring(arrow + 4));
            }
        }
        return parsed;
    }

    private static List<String> diff(Map<String, String> before, Map<String, String> after) {
        List<String> diffs = new ArrayList<>();
        Set<String> labels = new TreeSet<>(before.keySet());
        labels.addAll(after.keySet());
        for (String label : labels) {
            String oldValue = before.get(label);
            String newValue = after.get(label);
            if (oldValue == null) {
                diffs.add("＋ 新增语料「%s」=> %s".formatted(label, newValue));
            } else if (newValue == null) {
                diffs.add("－ 移除语料「%s」（原 => %s）".formatted(label, oldValue));
            } else if (!oldValue.equals(newValue)) {
                diffs.add("≠ 判定变化「%s」%n    旧: %s%n    新: %s".formatted(label, oldValue, newValue));
            }
        }
        return diffs;
    }

    // ===================== 内部工具 =====================

    private static void assertCues(List<String> cues, Predicate<String> signal, String name) {
        for (String cue : cues) {
            assertThat(signal.test(cue)).as("「%s」应点亮 %s", cue, name).isTrue();
            if ("hasPurchaseIntent".equals(name)) {
                // 购买意图是**唯一**不单独路由的信号（2026-11-05）：只写"买"没商品时，
                // 工作流里没有可下单的东西，会空转成"没能给出有效回答"，所以改走直答。
                // 这里的契约改成双向等价：能定位到商品 ⇔ 才进工作流。
                assertThat(QuestionSignals.needsAgentWorkflow(cue))
                        .as("「%s」的路由应由「能否定位到商品」决定", cue)
                        .isEqualTo(QuestionSignals.purchaseProductId(cue) != null);
                continue;
            }
            assertThat(QuestionSignals.needsAgentWorkflow(cue))
                    .as("「%s」点亮了 %s，就应该能路由进工作流", cue, name)
                    .isTrue();
        }
    }

    /** `QuestionSignals` 所有 public static（单 String 入参）方法名。 */
    private static Set<String> publicSignalNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Method method : signalMethods()) {
            names.add(method.getName());
        }
        return names;
    }

    private static List<Method> signalMethods() {
        List<Method> methods = new ArrayList<>();
        for (Method method : QuestionSignals.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers())
                    && method.getParameterCount() == 1 && method.getParameterTypes()[0] == String.class) {
                methods.add(method);
            }
        }
        methods.sort(Comparator.comparing(Method::getName));
        return methods;
    }

    /** 语料 = 每张表的每个词条 + 真实问法，label 保证可读可 diff（TreeMap 固定顺序）。 */
    private static Map<String, String> corpus() {
        Map<String, String> corpus = new TreeMap<>();
        // 标签里带词条本身（不用下标）：表里插一个词只多一行，不会把后面的行全挤成"变了"
        QuestionSignals.cueTables().forEach((table, cues) ->
                cues.forEach(cue -> corpus.put("TABLE:" + table + "=" + cue, cue)));
        REAL_QUESTIONS.forEach(question -> corpus.put("QUESTION=" + question, question));
        return corpus;
    }

    private static List<String> allCorpus() {
        return new ArrayList<>(corpus().values());
    }

    private static String renderMatrix() throws Exception {
        StringBuilder out = new StringBuilder();
        out.append("# QuestionSignals 影响面矩阵（自动生成，勿手改）\n")
                .append("# 每行：<语料标签> => <被点亮的信号，按名字排序；未触发的省略>\n")
                .append("# 重新生成：mvn -D").append(REGENERATE_FLAG)
                .append("=true -Dtest=QuestionSignalsImpactTest test\n")
                .append("# 表里的裸词看不出组合效果，真实问法与反例在 QUESTION[*] 段。\n\n");
        for (Map.Entry<String, String> entry : corpus().entrySet()) {
            out.append(entry.getKey()).append(" => ").append(digest(entry.getValue())).append('\n');
        }
        return out.toString();
    }

    /** 一条语料上"点亮了哪些信号"，非 boolean 信号带上取值。 */
    private static String digest(String message) throws Exception {
        List<String> fired = new ArrayList<>();
        for (Method method : signalMethods()) {
            Object value = method.invoke(null, message);
            if (value instanceof Boolean flag) {
                if (flag) {
                    fired.add(method.getName());
                }
                continue;
            }
            if (value == null) {
                continue;
            }
            // 两个"几乎每条语料都会命中"的信号单独收敛，否则每一行都被它们刷屏、真正的变化看不到：
            // catalogQueryFor 在没补领域词时原样返回输入；extractPurchaseQuantity 没写数量时默认 1。
            String name = method.getName();
            if ("catalogQueryFor".equals(name)) {
                if (!String.valueOf(value).equals(message)) {
                    fired.add("catalogQueryFor=+领域词");
                }
                continue;
            }
            if ("extractPurchaseQuantity".equals(name) && Integer.valueOf(1).equals(value)) {
                continue;
            }
            // 这两个信号几乎每条语料都会命中（空串/空列表），不收敛就会把整份快照刷成噪音
            if ("orderNoIn".equals(name)) {
                if (!String.valueOf(value).isEmpty()) {
                    fired.add("orderNoIn=" + value);
                }
                continue;
            }
            if ("productItemsIn".equals(name)) {
                List<?> items = (List<?>) value;
                if (!items.isEmpty()) {
                    fired.add("productItemsIn=" + items.stream()
                            .map(item -> ((PurchaseItem) item).productId() + "×" + ((PurchaseItem) item).quantity())
                            .collect(java.util.stream.Collectors.joining("+")));
                }
                continue;
            }
            if ("purchaseItems".equals(name)) {
                // 只记"识别到了哪几件"（productId×数量），空列表不刷屏
                List<?> items = (List<?>) value;
                if (!items.isEmpty()) {
                    fired.add("purchaseItems=" + items.stream()
                            .map(item -> ((PurchaseItem) item).productId() + "×" + ((PurchaseItem) item).quantity())
                            .collect(java.util.stream.Collectors.joining("+")));
                }
                continue;
            }
            fired.add(name + "=" + value);
        }
        return fired.isEmpty() ? "-" : String.join(", ", fired);
    }
}
