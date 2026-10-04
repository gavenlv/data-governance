package com.datagovernance.api.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.api.security.Subjects;
import com.datagovernance.policy.AccessAuditService;
import com.datagovernance.policy.AccessPolicy;
import com.datagovernance.policy.AccessRequestService;
import com.datagovernance.policy.Subject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 访问治理：申请 / 审批 / 授权 / 复核 / 审计（docs/09 §9.7、docs/20 §8）。
 *
 * <p>这是本方案认定的核心差异化：**执行层是商品，生命周期层才是产品**。
 * 授权要求：读 {@code asset:read}；申请与复核 {@code governance:write}；
 * 审批额外要求审批人具备审批链中的角色（由服务层按流程判定，不只看权限点）。
 */
@RestController
@RequestMapping("/api/v1/access")
public class AccessController {

    private final AccessRequestService accessRequests;
    private final AccessAuditService audit;

    public AccessController(AccessRequestService accessRequests, AccessAuditService audit) {
        this.accessRequests = accessRequests;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ 申请

    @PostMapping("/requests")
    public Map<String, Object> submit(@RequestBody SubmitBody body) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        return accessRequests.submit(new AccessRequestService.SubmitRequest(
                subject.id(), body.resourceUrn(), body.columnName(), body.granularity(),
                body.permissions(), body.purpose(), body.durationDays()));
    }

    @GetMapping("/requests")
    public Map<String, Object> listRequests(@RequestParam(required = false) String status,
                                            @RequestParam(required = false) String requester,
                                            @RequestParam(defaultValue = "100") int limit) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        List<Map<String, Object>> rows = accessRequests.listRequests(status, requester, limit);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", rows.size());
        payload.put("requests", rows);
        return payload;
    }

    /** 审批：批准即生成带到期时间的授权记录。 */
    @PostMapping("/requests/{id}/decide")
    public Map<String, Object> decide(@PathVariable long id, @RequestBody DecideBody body) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        return accessRequests.decide(id, body.decision(), subject.id(),
                List.copyOf(subject.roles()), body.note());
    }

    @PostMapping("/requests/{id}/withdraw")
    public Map<String, Object> withdraw(@PathVariable long id) {
        Subject subject = Subjects.require();
        return accessRequests.withdraw(id, subject.id());
    }

    // ------------------------------------------------------------------ 授权

    @GetMapping("/grants")
    public Map<String, Object> listGrants(@RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "100") int limit) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        List<Map<String, Object>> rows = accessRequests.listGrants(status, limit);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", rows.size());
        payload.put("grants", rows);
        return payload;
    }

    @PostMapping("/grants/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable long id, @RequestBody RevokeBody body) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        return accessRequests.revoke(id, subject.id(), body.reason());
    }

    /** 手动触发一次到期回收（排障用；常驻回收由 AccessGrantExpiryJob 负责）。 */
    @PostMapping("/grants/expire-sweep")
    public Map<String, Object> expireSweep() {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        return accessRequests.expireGrants();
    }

    /** 治理概览：待办、超期、即将到期、粒度分布。 */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        return accessRequests.overview();
    }

    // ------------------------------------------------------------------ 复核

    @GetMapping("/reviews/{campaign}")
    public Map<String, Object> reviewCampaign(@PathVariable String campaign,
                                              @RequestParam(defaultValue = "50") int limit) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        return accessRequests.reviewCampaign(campaign, subject.id(), limit);
    }

    @PostMapping("/reviews/{campaign}/decide")
    public Map<String, Object> decideReview(@PathVariable String campaign,
                                            @RequestBody ReviewBody body) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        return accessRequests.recordReview(campaign, body.grantId(), subject.id(),
                body.decision(), body.reason());
    }

    // ------------------------------------------------------------------ 审计

    @GetMapping("/events")
    public Map<String, Object> events(@RequestParam(required = false) String subject,
                                      @RequestParam(required = false) String resourceUrn,
                                      @RequestParam(required = false) String source,
                                      @RequestParam(required = false) Integer days,
                                      @RequestParam(defaultValue = "200") int limit) {
        Subject caller = Subjects.require();
        AccessPolicy.authorize(caller, "asset:read");
        List<Map<String, Object>> rows = audit.events(subject, resourceUrn, source, days, limit);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", rows.size());
        payload.put("events", rows);
        return payload;
    }

    /** 合规审计报告（含**审计覆盖范围**说明：没有记录 ≠ 没有发生）。 */
    @GetMapping("/audit-report")
    public Map<String, Object> auditReport(@RequestParam(defaultValue = "90") int days) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        return audit.report(days);
    }

    /** 最小权限复盘。 */
    @GetMapping("/least-privilege")
    public Map<String, Object> leastPrivilege(@RequestParam(defaultValue = "200") int limit) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        return audit.leastPrivilegeReview(limit);
    }

    // ------------------------------------------------------------------ 请求体

    public record SubmitBody(String resourceUrn, String columnName, String granularity,
                             List<String> permissions, String purpose, Integer durationDays) {
    }

    public record DecideBody(String decision, String note) {
    }

    public record RevokeBody(String reason) {
    }

    public record ReviewBody(long grantId, String decision, String reason) {
    }
}
