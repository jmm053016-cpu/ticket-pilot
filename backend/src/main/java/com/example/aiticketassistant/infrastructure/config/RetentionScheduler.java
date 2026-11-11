package com.example.aiticketassistant.infrastructure.config;

import com.example.aiticketassistant.application.maintenance.RetentionCleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 数据清理定时任务（2026-11-09）。
 *
 * <p>默认**关闭**（`assistant.retention.enabled=false`）：清理是破坏性操作，演示环境不该被后台任务动数据。
 * 需要时打开，或直接调用 {@code RetentionCleanupService.runOnce()}。
 *
 * <p>用 `@Scheduled` 而不是自己做线程池：Spring 的调度器已经处理了单线程串行与异常隔离。
 */
@Component
@ConditionalOnProperty(name = "assistant.retention.enabled", havingValue = "true")
public class RetentionScheduler {
    private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);

    private final RetentionCleanupService cleanupService;

    public RetentionScheduler(RetentionCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    /** 每天凌晨 3:30（cron 可配）。 */
    @Scheduled(cron = "${assistant.retention.cron:0 30 3 * * *}")
    public void cleanup() {
        try {
            cleanupService.runOnce();
        } catch (Exception ex) {
            log.warn("数据清理失败（不影响业务）：{}", ex.getMessage(), ex);
        }
    }
}
