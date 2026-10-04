package com.datagovernance.api.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.api.security.Subjects;
import com.datagovernance.policy.AccessPolicy;
import com.datagovernance.policy.PolicyCompiler;
import com.datagovernance.policy.PolicyService;
import com.datagovernance.policy.Subject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 策略建模、编译、下发、回滚与覆盖率（docs/09 §9.7、ADR-008、docs/20 §8）。
 *
 * <p>授权：读 {@code asset:read}；建模/编译/下发 {@code governance:write}。
 *
 * <p>诚实边界（写在接口层，避免被误读）：<b>本接口只产出产物，不直接配置执行引擎。</b>
 * 执行层（Trino SystemAccessControl / Ranger / 数仓原生策略）由现成产品承担，
 * 平台负责"业务可读的建模 + 可信可审计可回滚的产物 + 覆盖率度量"。
 */
@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {

    private final PolicyService policies;

    public PolicyController(PolicyService policies) {
        this.policies = policies;
    }

    /** 建模：新建/更新策略（建模即编译，编译不过不入库）。 */
    @PostMapping
    public Map<String, Object> upsert(@RequestBody Map<String, Object> document) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        return policies.upsertDefinition(document, subject.id());
    }

    @GetMapping
    public Map<String, Object> list() {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        List<Map<String, Object>> rows = policies.listDefinitions();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", rows.size());
        payload.put("policies", rows);
        payload.put("targets", List.of(
                Map.of("id", "trino", "note", "行过滤谓词 + 列掩码表达式（首选集成点）"),
                Map.of("id", "warehouse", "note", "GRANT/REVOKE + 行访问策略 / 列掩码（数仓原生）"),
                Map.of("id", "bi", "note", "数据集可见性与字段隐藏（**不是安全边界**）"),
                Map.of("id", "sdk", "note", "令牌声明中的可见范围（仅辅助）")));
        return payload;
    }

    /** 只编译不落库：让使用者先看产物（策略错误的后果是数据泄露或大面积不可用）。 */
    @PostMapping("/compile-preview")
    public Map<String, Object> compilePreview(@RequestBody Map<String, Object> document) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        PolicyCompiler.PolicySpec spec = PolicyService.toSpec(document);
        PolicyCompiler.CompiledPolicy compiled = PolicyCompiler.compile(spec);
        Map<String, Object> payload = new LinkedHashMap<>(compiled.asMap());
        payload.put("errors", spec.validate());
        payload.put("note", "编译预览不写库；产物里带注释头（策略名/版本/资源范围），"
                + "因为下发的产物最终要被人审阅 —— 没有出处的谓词无法审计");
        return payload;
    }

    @PostMapping("/compile")
    public Map<String, Object> compile(@RequestParam(required = false) String target) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        return policies.compileAll(target);
    }

    /** 下发：把某目标的产物打包（默认落盘），记录部署与产物哈希。 */
    @PostMapping("/deploy")
    public Map<String, Object> deploy(@RequestParam String target) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        return policies.deploy(target, subject.id());
    }

    @GetMapping("/deployments")
    public Map<String, Object> deployments(@RequestParam(required = false) String target,
                                           @RequestParam(defaultValue = "50") int limit) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        List<Map<String, Object>> rows = policies.listDeployments(target, limit);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", rows.size());
        payload.put("deployments", rows);
        return payload;
    }

    @PostMapping("/deployments/{id}/rollback")
    public Map<String, Object> rollback(@PathVariable long id, @RequestBody(required = false) RollbackBody body) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        return policies.rollback(id, subject.id(), body == null ? null : body.reason());
    }

    /** 覆盖率度量（**最关键的一个接口**：宣称已下发而不度量覆盖率是最危险的表述）。 */
    @PostMapping("/coverage")
    public Map<String, Object> measureCoverage() {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        return policies.measureCoverage(subject.id());
    }

    @GetMapping("/coverage")
    public Map<String, Object> coverageHistory(@RequestParam(defaultValue = "20") int limit) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        List<Map<String, Object>> rows = policies.coverageHistory(limit);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", rows.size());
        payload.put("history", rows);
        return payload;
    }

    public record RollbackBody(String reason) {
    }
}
