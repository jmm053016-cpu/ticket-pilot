package com.example.aiticketassistant.application.tool;

import java.util.Set;

public final class ToolNames {
    public static final String CREATE_TICKET = "CREATE_TICKET";
    public static final String QUERY_ORDER = "QUERY_ORDER";
    public static final String UPDATE_TICKET = "UPDATE_TICKET";
    public static final String SEARCH_KNOWLEDGE = "SEARCH_KNOWLEDGE";
    public static final String CREATE_ORDER = "CREATE_ORDER";
    /** 预算测算：最多能买几件 / 最便宜组合。纯计算、只读。 */
    public static final String PLAN_BUDGET = "PLAN_BUDGET";
    /** 购物车：加购（真落库，不锁库存、不生成订单）。 */
    public static final String ADD_TO_CART = "ADD_TO_CART";
    /** 购物车：查看。 */
    public static final String VIEW_CART = "VIEW_CART";
    /** 账户查询：地址 / 支付方式 / 可用优惠券（只读、脱敏）。 */
    public static final String QUERY_ACCOUNT = "QUERY_ACCOUNT";
    /** 取消订单：仅未支付订单，取消后释放预留库存。 */
    public static final String CANCEL_ORDER = "CANCEL_ORDER";
    /** 退款申请：只落申请单，不动钱。 */
    public static final String REQUEST_REFUND = "REQUEST_REFUND";
    /** 退款进度查询（按订单号或最近若干条）。 */
    public static final String QUERY_REFUND = "QUERY_REFUND";
    /** 退货申请：货寄回，人工核验后处理退款；不动钱。 */
    public static final String REQUEST_RETURN = "REQUEST_RETURN";
    /** 退货进度查询。 */
    public static final String QUERY_RETURN = "QUERY_RETURN";

    private static final Set<String> AI_CALLABLE_TOOLS = Set.of(
            CREATE_TICKET,
            QUERY_ORDER,
            UPDATE_TICKET,
            SEARCH_KNOWLEDGE,
            CREATE_ORDER,
            PLAN_BUDGET,
            ADD_TO_CART,
            VIEW_CART,
            QUERY_ACCOUNT,
            CANCEL_ORDER,
            REQUEST_REFUND,
            QUERY_REFUND,
            REQUEST_RETURN,
            QUERY_RETURN);

    public static boolean isAiCallable(String toolName) {
        return AI_CALLABLE_TOOLS.contains(toolName);
    }

    private ToolNames() {}
}
