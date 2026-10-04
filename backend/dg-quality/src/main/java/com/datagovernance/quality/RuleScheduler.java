package com.datagovernance.quality;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 质量规则调度器（docs/09 §9.4）。
 *
 * <p>与采集调度器同样的取舍：互斥不在应用层做，多副本同时轮询是安全的 ——
 * 认领规则用数据库的 {@code FOR UPDATE SKIP LOCKED} 语义（这里用"更新 next_run_at 的行数"
 * 做等价认领），因此不会出现两个副本重复执行同一条规则。
 */
@Component
public class RuleScheduler {

    private static final Logger log = LoggerFactory.getLogger(RuleScheduler.class);

    private final RuleExecutionService rules;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public RuleScheduler(RuleExecutionService rules,
                         @Value("${dg.quality.scheduler-enabled:true}") boolean enabled) {
        this.rules = rules;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${dg.quality.poll-interval-ms:60000}",
            initialDelayString = "${dg.quality.initial-delay-ms:30000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            int initialized = rules.initializeMissingNextRun();
            if (initialized > 0) {
                log.info("补算 {} 条质量规则的下次执行时间", initialized);
            }
            var result = rules.runDue("scheduler");
            int due = ((Number) result.get("due")).intValue();
            if (due > 0) {
                log.info("质量规则调度：执行 {} 条到期规则", due);
            }
        } catch (RuntimeException e) {
            log.warn("质量规则调度失败（不影响控制面）：{}", e.getMessage());
        } finally {
            running.set(false);
        }
    }
}
