package com.datagovernance.api.jobs;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.datagovernance.core.search.SearchIndexConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 检索索引的常驻消费者（docs/09 §9.3、ADR-002）。
 *
 * <p>显式参数化，不依赖框架默认值 —— 默认值在生产语义下未必正确：
 * "消费跟不上时持续堆积"与"一次消费长时间持有写事务"必须能分别调。
 *
 * <p>关闭方式：{@code dg.search.consume-interval-ms=0}。关闭后索引只能通过
 * {@code POST /api/v1/index/consume} 手动推进；接口会回报 {@code lag}，
 * 因此"消费者停了"是可观测的，不会变成搜索悄悄变旧。
 *
 * <p><b>为什么不用 {@code @Scheduled}</b>：注解里的 {@code fixedDelayString} 必须是正数，
 * 传 0 会在容器启动时抛 {@code IllegalArgumentException} —— 也就是说本类原先声明的
 * "设 0 关闭"其实**从未生效**，只会把控制面直接弄挂（docs/21 §22 记录了这个缺陷）。
 * 现在改为按配置显式调度：0 就不创建调度线程，语义与文档一致。
 */
@Component
public class IndexMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(IndexMaintenanceJob.class);

    private final SearchIndexConsumer consumer;
    private final long intervalMillis;
    private final long initialDelayMillis;
    private final int batchSize;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ScheduledThreadPoolExecutor scheduler;

    public IndexMaintenanceJob(
            SearchIndexConsumer consumer,
            @Value("${dg.search.consume-interval-ms:5000}") long intervalMillis,
            @Value("${dg.search.consume-initial-delay-ms:10000}") long initialDelayMillis,
            @Value("${dg.search.consume-batch-size:500}") int batchSize) {
        this.consumer = consumer;
        this.intervalMillis = intervalMillis;
        this.initialDelayMillis = Math.max(0, initialDelayMillis);
        this.batchSize = Math.max(1, batchSize);
    }

    @PostConstruct
    void start() {
        if (intervalMillis <= 0) {
            log.info("检索索引常驻消费者已关闭（dg.search.consume-interval-ms=0）："
                    + "索引只能通过 POST /api/v1/index/consume 手动推进，lag 会持续暴露");
            return;
        }
        scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "dg-index-consumer");
            thread.setDaemon(true);
            return thread;
        });
        // 用固定延迟（上一轮结束后再计时）而不是固定频率：后者在负载高时会叠加执行，
        // 同一批事件被多个线程同时消费 —— 写是幂等的，但事件流读会被放大成 N 倍。
        scheduler.scheduleWithFixedDelay(this::consumeSafely,
                initialDelayMillis, intervalMillis, TimeUnit.MILLISECONDS);
        log.info("检索索引常驻消费者已启动：间隔 {} ms，初始延迟 {} ms，批量 {}",
                intervalMillis, initialDelayMillis, batchSize);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** 单轮消费；手动接口 {@code POST /api/v1/index/consume} 也复用它。 */
    public void consumeSafely() {
        if (!running.compareAndSet(false, true)) {
            return; // 上一轮未结束（手动触发与大延迟叠加时可能发生）
        }
        try {
            SearchIndexConsumer.ConsumeStats stats = consumer.consume(batchSize, 10);
            if (stats.processed() > 0) {
                log.info("检索索引增量消费：处理 {} 事件（seq {} → {}），写入 {} 文档",
                        stats.processed(), stats.fromSeq(), stats.toSeq(), stats.indexed());
            }
        } catch (RuntimeException e) {
            // 消费失败不能拖垮控制面；lag 会持续增长并被 /api/v1/index/lag 暴露出来
            log.warn("检索索引消费失败（lag 将增长并在 /api/v1/index/lag 暴露）：{}", e.getMessage());
        } finally {
            running.set(false);
        }
    }
}
