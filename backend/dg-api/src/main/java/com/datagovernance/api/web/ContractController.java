package com.datagovernance.api.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.api.security.Subjects;
import com.datagovernance.policy.AccessPolicy;
import com.datagovernance.policy.Subject;
import com.datagovernance.quality.CiGateService;
import com.datagovernance.quality.ContractService;
import com.datagovernance.quality.ContractViolationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据契约：注册、版本、兼容性 diff、违约、消费者、CI 门禁（docs/09 §9.5）。
 *
 * <p>授权：读取 {@code asset:read}；注册契约与处理违约 {@code governance:write}。
 * CI 门禁用同一套接口（脚本带令牌调用），因此门禁判定与平台内判定不会分叉。
 */
@RestController
@RequestMapping("/api/v1/contracts")
public class ContractController {

    private final ContractService contracts;
    private final ContractViolationService violations;
    private final CiGateService gate;

    public ContractController(ContractService contracts, ContractViolationService violations,
                              CiGateService gate) {
        this.contracts = contracts;
        this.violations = violations;
        this.gate = gate;
    }

    // ------------------------------------------------------------------ 契约

    @PostMapping
    public Map<String, Object> register(@RequestBody RegisterRequest request) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        return contracts.register(request.document(),
                request.namespace() == null ? "prod" : request.namespace(),
                subject.id(),
                Boolean.TRUE.equals(request.allowBreaking()),
                request.breakingJustification(),
                Boolean.TRUE.equals(request.dryRun()));
    }

    @GetMapping
    public Map<String, Object> list() {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        Map<String, Object> payload = new LinkedHashMap<>();
        List<Map<String, Object>> rows = contracts.list();
        payload.put("count", rows.size());
        payload.put("contracts", rows);
        return payload;
    }

    @GetMapping("/{urn}")
    public Map<String, Object> get(@PathVariable String urn) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        return contracts.get(urn);
    }

    @GetMapping("/{urn}/versions")
    public Map<String, Object> versions(@PathVariable String urn) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        List<Map<String, Object>> rows = contracts.versions(urn);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", rows.size());
        payload.put("versions", rows);
        payload.put("note", "版本历史直接来自 aspect_history：契约是一等实体，因此不需要单独一套版本存储");
        return payload;
    }

    @GetMapping("/{urn}/diff")
    public Map<String, Object> diff(@PathVariable String urn,
                                    @RequestParam long from,
                                    @RequestParam long to) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        return contracts.diffVersions(urn, from, to);
    }

    // ------------------------------------------------------------ 违约与校验

    /** 运行时校验：契约 vs 数据集实际结构。 */
    @PostMapping("/{urn}/validate")
    public Map<String, Object> validate(@PathVariable String urn) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        return violations.validate(urn, subject.id());
    }

    @GetMapping("/violations")
    public Map<String, Object> violations(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) String severity,
                                          @RequestParam(defaultValue = "100") int limit) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        List<Map<String, Object>> rows = violations.list(status, severity, limit);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", rows.size());
        payload.put("violations", rows);
        return payload;
    }

    @GetMapping("/violations/overview")
    public Map<String, Object> violationOverview() {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        return violations.overview();
    }

    @PostMapping("/violations/{id}/ack")
    public Map<String, Object> acknowledge(@PathVariable long id) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        return violations.acknowledge(id, subject.id());
    }

    /** 豁免（必须带到期时间：拒绝永久豁免）。 */
    @PostMapping("/violations/{id}/exempt")
    public Map<String, Object> exempt(@PathVariable long id, @RequestBody ExemptRequest request) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        Instant until = request.until() == null || request.until().isBlank()
                ? null : Instant.parse(request.until());
        return violations.exempt(id, until, request.reason(), subject.id());
    }

    // ------------------------------------------------------------ 消费者关系

    @GetMapping("/{urn}/consumers")
    public Map<String, Object> consumers(@PathVariable String urn) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        return contracts.consumersReport(urn);
    }

    @PostMapping("/{urn}/consumers")
    public Map<String, Object> addConsumer(@PathVariable String urn,
                                           @RequestBody ConsumerRequest request) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        return contracts.addConsumer(urn, request.consumerUrn(), subject.id(), request.note());
    }

    @DeleteMapping("/{urn}/consumers")
    public Map<String, Object> removeConsumer(@PathVariable String urn,
                                              @RequestParam String consumerUrn) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        boolean removed = contracts.removeConsumer(urn, consumerUrn);
        return Map.of("contract", urn, "consumer", consumerUrn, "removed", removed);
    }

    // --------------------------------------------------------------- CI 门禁

    /** CI 门禁检查（脚本直接调用；响应里的 exitCode 就是脚本应使用的退出码）。 */
    @PostMapping("/ci-check")
    public Map<String, Object> ciCheck(@RequestBody CiCheckRequest request) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");

        // allowBreaking 是顶层开关：早期实现只在提供了 policy 对象时才读它，
        // 导致"没带 policy 时 allowBreaking 被静默忽略"（真实踩到的 bug）
        boolean allowBreaking = Boolean.TRUE.equals(request.allowBreaking());
        CiGateService.GatePolicy policy = request.policy() == null
                ? new CiGateService.GatePolicy(false, false, false, false, 0, allowBreaking)
                : new CiGateService.GatePolicy(
                        Boolean.TRUE.equals(request.policy().requireOwner()),
                        Boolean.TRUE.equals(request.policy().requireDescription()),
                        Boolean.TRUE.equals(request.policy().requireClassification()),
                        Boolean.TRUE.equals(request.policy().requireNoUndetectedConsumers()),
                        request.policy().maxDownstreamForBreaking() == null
                                ? 0 : request.policy().maxDownstreamForBreaking(),
                        allowBreaking);
        return gate.check(request.document(),
                request.namespace() == null ? "prod" : request.namespace(),
                policy, false, request.source() == null ? "api" : request.source(), subject.id());
    }

    /** 门禁历史：这个契约被拦过几次、为什么。 */
    @GetMapping("/ci-history")
    public Map<String, Object> ciHistory(@RequestParam(required = false) String urn,
                                         @RequestParam(defaultValue = "50") int limit) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        List<Map<String, Object>> rows = gate.history(urn, limit);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", rows.size());
        payload.put("checks", rows);
        return payload;
    }

    // ------------------------------------------------------------------ 请求体

    public record RegisterRequest(
            Map<String, Object> document,
            String namespace,
            Boolean allowBreaking,
            String breakingJustification,
            Boolean dryRun) {
    }

    public record ExemptRequest(String until, String reason) {
    }

    public record ConsumerRequest(String consumerUrn, String note) {
    }

    public record CiCheckRequest(
            Map<String, Object> document,
            String namespace,
            PolicySpec policy,
            Boolean allowBreaking,
            String source) {

        public record PolicySpec(
                Boolean requireOwner,
                Boolean requireDescription,
                Boolean requireClassification,
                Boolean requireNoUndetectedConsumers,
                Integer maxDownstreamForBreaking) {
        }
    }
}
