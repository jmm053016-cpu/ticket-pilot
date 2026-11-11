package com.example.aiticketassistant.application.catalog;

import com.example.aiticketassistant.domain.catalog.Product;
import com.example.aiticketassistant.domain.catalog.ProductRepository;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「只说了品类，没说是哪一件」时给出候选（2026-11-11）。
 *
 * <p>背景：用户说「我要买一件羽绒服」，以前系统会**替他挑一件**（"黑色短款羽绒服 ¥899"）直接下水单确认单。
 * 确认卡片虽然能兜底，但等于替用户做了选品决定。现在的做法是：**该品类下有多个 SKU 时列 2~3 个候选 + 反问**
 * （该品类只有一个 SKU 时仍然直接下单 —— 比如「帆布鞋」在目录里只有一件，不算歧义）。
 *
 * <p>为什么按"目录里有几个 SKU"判断而不是维护一张"品类词/具体名"的表：
 * 歧义与否是**数据属性**，加了新商品就该自动生效；手写词表迟早和目录脱节（这个项目已经踩过一次：
 * 工具加了但提示词没写）。
 */
@Service
public class CategoryCandidateService {
    /** 候选最多给几个：多了用户选择成本高，少了覆盖不了。 */
    private static final int MAX_CANDIDATES = 3;

    /**
     * 用于"按品类找候选"的词。只用来**在目录里做名称子串匹配**，
     * 不做"是不是具体商品"的判断（那个交给 SKU 数量）。
     */
    private static final List<String> CATEGORY_WORDS = List.of(
            "T恤", "衬衫", "卫衣", "牛仔裤", "连衣裙", "夹克", "半身裙", "polo衫", "大衣", "毛衣",
            "开衫", "休闲裤", "长裤", "裤子", "短裤", "风衣", "羽绒服", "运动鞋", "帆布鞋", "双肩包",
            "围巾", "外套", "鞋", "靴", "帽", "裙");

    private final ProductRepository productRepository;

    public CategoryCandidateService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /**
     * @return 当"只说了品类且该品类有多个 SKU"时返回候选；其余情况返回空（照常按具体商品处理）
     */
    @Transactional(readOnly = true)
    public Optional<Candidates> findCandidates(String message) {
        String value = message == null ? "" : message.toLowerCase();
        if (value.isBlank()) {
            return Optional.empty();
        }
        List<Product> active = productRepository.findActiveProducts();
        if (active.isEmpty()) {
            return Optional.empty();
        }
        for (String category : CATEGORY_WORDS) {
            if (!value.contains(category.toLowerCase())) {
                continue;
            }
            List<Product> matched = active.stream()
                    .filter(product -> product.name() != null && product.name().contains(category))
                    .toList();
            // 句子里已经出现了完整商品名（比品类词更长的名称）→ 用户说的是具体款，不是品类
            boolean specific = active.stream()
                    .filter(product -> product.name() != null && product.name().length() > category.length())
                    .anyMatch(product -> value.contains(product.name().toLowerCase()));
            if (specific) {
                return Optional.empty();
            }
            // 只有 1 个 SKU 就不算歧义（「帆布鞋」目录里只有一件），交给正常下单
            if (matched.size() < 2) {
                return Optional.empty();
            }
            // 更具体的品类优先：同一句里既出现"夹克"又出现"工装夹克"时，用命中 SKU 更少的那个
            List<Product> picked = matched.stream()
                    .sorted((left, right) -> left.price().compareTo(right.price()))
                    .limit(MAX_CANDIDATES)
                    .toList();
            return Optional.of(new Candidates(category, picked.stream()
                    .map(product -> new Candidate(product.id(), product.name(), product.price()))
                    .toList(), matched.size()));
        }
        return Optional.empty();
    }

    /** 命中品类及其候选。 */
    public record Candidates(String category, List<Candidate> candidates, int totalInCategory) {

        /** 给回答用的结构化摘要（前端/日志都能读）。 */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("category", category);
            map.put("totalInCategory", totalInCategory);
            map.put("candidates", candidates.stream()
                    .map(candidate -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("productId", candidate.productId());
                        item.put("name", candidate.name());
                        item.put("price", candidate.price() == null ? "" : candidate.price().toPlainString());
                        return item;
                    })
                    .toList());
            return map;
        }
    }

    public record Candidate(String productId, String name, BigDecimal price) {}
}
