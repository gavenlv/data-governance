package com.datagovernance.policy;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 授权到期回收的定时任务（docs/09 §9.7）。
 *
 * <p>为什么它必须是常驻任务而不是"人工巡检"：**只发不回收的授权体系等价于没有期限**。
 * 到期回收一旦依赖人的自觉，权限就会自然演变为永久权限 —— 这是权限治理最普遍的失效方式。
 */
@Component
public class AccessGrantExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(AccessGrantExpiryJob.class);

    private final AccessRequestService accessRequests;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AccessGrantExpiryJob(AccessRequestService accessRequests,
                                @Value("${dg.policy.expiry-job-enabled:true}") boolean enabled) {
        this.accessRequests = accessRequests;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${dg.policy.expiry-interval-ms:300000}",
            initialDelayString = "${dg.policy.expiry-initial-delay-ms:60000}")
    public void sweep() {
        if (!enabled || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            var result = accessRequests.expireGrants();
            int grants = ((Number) result.get("expiredGrants")).intValue();
            int requests = ((Number) result.get("expiredRequests")).intValue();
            if (grants > 0 || requests > 0) {
                log.info("访问授权到期回收：授权 {} 条、僵尸申请 {} 条", grants, requests);
            }
        } catch (RuntimeException e) {
            log.warn("授权到期回收失败（下次重试）：{}", e.getMessage());
        } finally {
            running.set(false);
        }
    }
}
