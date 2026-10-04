package com.datagovernance.ingestion.schedule;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 常驻调度循环（docs/09 §9.1）。
 *
 * <p>每轮做两件事：给"启用了但还没算过下次执行时间"的调度补算时间；把到期的调度跑掉。
 * 真正的互斥在 {@link ScheduleService#run} 里（PG advisory lock），因此
 * <b>多副本同时轮询是安全的</b> —— 这正是把互斥下沉到数据库而不是放在应用层的原因。
 *
 * <p>用 {@code fixedDelay}（上一轮结束后再计时）：{@code fixedRate} 在采集较慢时
 * 会让同一调度被反复触发，虽然 advisory lock 会挡住重复执行，但会产生大量
 * "skipped" 噪音，掩盖真正的调度问题。
 */
@Component
public class ScheduleRunner {

    private static final Logger log = LoggerFactory.getLogger(ScheduleRunner.class);

    private final ScheduleService schedules;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ScheduleRunner(ScheduleService schedules,
                          @Value("${dg.scheduler.enabled:true}") boolean enabled) {
        this.schedules = schedules;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${dg.scheduler.poll-interval-ms:30000}",
            initialDelayString = "${dg.scheduler.initial-delay-ms:20000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            int initialized = schedules.initializeMissingNextRun();
            if (initialized > 0) {
                log.info("补算 {} 条调度的下次执行时间", initialized);
            }
            List<String> due = schedules.due(Instant.now());
            for (String name : due) {
                try {
                    var result = schedules.run(name, false, "scheduler");
                    if (Boolean.TRUE.equals(result.get("skipped"))) {
                        log.info("调度 {} 跳过：{}", name, result.get("reason"));
                    } else {
                        log.info("调度 {} 执行结果：{}", name,
                                result.containsKey("run") ? "已记录批次" : result.get("error"));
                    }
                } catch (RuntimeException e) {
                    // 单条调度失败不能停掉整个循环（否则一条坏配置会让所有调度停摆）
                    log.warn("调度 {} 抛出异常（循环继续）：{}", name, e.getMessage());
                }
            }
        } finally {
            running.set(false);
        }
    }
}
