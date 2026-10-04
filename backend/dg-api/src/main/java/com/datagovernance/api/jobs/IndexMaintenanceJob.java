package com.datagovernance.api.jobs;

import java.util.concurrent.atomic.AtomicBoolean;

import com.datagovernance.core.search.SearchIndexConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
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
 */
@Component
public class IndexMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(IndexMaintenanceJob.class);

    private final SearchIndexConsumer consumer;
    private final long intervalMillis;
    private final int batchSize;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public IndexMaintenanceJob(
            SearchIndexConsumer consumer,
            @Value("${dg.search.consume-interval-ms:5000}") long intervalMillis,
            @Value("${dg.search.consume-batch-size:500}") int batchSize) {
        this.consumer = consumer;
        this.intervalMillis = intervalMillis;
        this.batchSize = Math.max(1, batchSize);
    }

    /**
     * 固定延迟消费。
     *
     * <p>用 {@code fixedDelay}（上一轮结束后再计时）而不是 {@code fixedRate}：
     * 后者在负载高时会并发叠加执行，出现"同一批事件被两个线程同时消费"的假象，
     * 虽然写是幂等的，但会把事件流读放大成 N 倍。
     */
    @Scheduled(fixedDelayString = "${dg.search.consume-interval-ms:5000}",
            initialDelayString = "${dg.search.consume-initial-delay-ms:10000}")
    public void consume() {
        if (intervalMillis <= 0) {
            return; // 显式关闭
        }
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
