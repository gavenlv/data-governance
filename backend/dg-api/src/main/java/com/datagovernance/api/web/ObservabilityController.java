package com.datagovernance.api.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.api.security.Subjects;
import com.datagovernance.policy.AccessPolicy;
import com.datagovernance.policy.Subject;
import com.datagovernance.quality.AnomalyService;
import com.datagovernance.quality.SloService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 可观测性：异常检测、SLO 达成率、事故闭环（docs/09 §9.4、docs/19）。
 *
 * <p>这个控制器把三件事串成一条链：<b>指标越界（异常）→ 达成率受损（SLO）→ 开事故 → 解决时必须沉淀规则</b>。
 * 三者分开做都会退化成"看板"，串起来才叫治理运营。
 */
@RestController
@RequestMapping("/api/v1/observability")
public class ObservabilityController {

    private final AnomalyService anomalies;
    private final SloService slos;

    public ObservabilityController(AnomalyService anomalies, SloService slos) {
        this.anomalies = anomalies;
        this.slos = slos;
    }

    // ------------------------------------------------------------------ 异常

    /**
     * 扫描异常。
     *
     * <p>默认只扫全部数据集；{@code datasetUrn} / {@code metric} 可收窄。
     * 返回里同时给出 {@code skipped}（样本不足而未判定的序列）——
     * <b>没判定不等于正常</b>，这个区分必须留在响应里。
     */
    @PostMapping("/anomalies/scan")
    public Map<String, Object> scan(@RequestBody(required = false) Map<String, Object> body) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        Map<String, Object> input = body == null ? Map.of() : body;
        return anomalies.scan(new AnomalyService.ScanRequest(
                str(input.get("datasetUrn")),
                str(input.get("metric")),
                str(input.get("method")),
                num(input.get("zThreshold")),
                intOf(input.get("lookbackDays")),
                intOf(input.get("seasonalPeriod"))), subject.id());
    }

    @GetMapping("/anomalies")
    public Map<String, Object> listAnomalies(@RequestParam(defaultValue = "false") boolean includeSuppressed,
                                             @RequestParam(defaultValue = "100") int limit) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        List<Map<String, Object>> rows = anomalies.list(includeSuppressed, limit);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", rows.size());
        payload.put("anomalies", rows);
        payload.put("note", "suppressed=true 表示该异常由上游异常传导而来，已抑制以免告警风暴");
        return payload;
    }

    @GetMapping("/anomalies/overview")
    public Map<String, Object> anomalyOverview() {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        return anomalies.overview();
    }

    // -------------------------------------------------------------------- SLO

    /** 定义/更新 SLO（按 name 幂等 upsert）。 */
    @PostMapping("/slos")
    public Map<String, Object> upsertSlo(@RequestBody Map<String, Object> document) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        return slos.upsertSlo(document);
    }

    @GetMapping("/slos")
    public Map<String, Object> listSlos() {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        List<Map<String, Object>> rows = slos.listSlos();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", rows.size());
        payload.put("slos", rows);
        payload.put("note", "达成率来自平台实际数据（新鲜度取剖析时间、通过率取规则执行、可用性取采集运行）；"
                + "无数据时回报 no_data，不给假的 100%");
        return payload;
    }

    /** 计算并落库一名 SLO 的达成率快照。 */
    @PostMapping("/slos/{name}/measure")
    public Map<String, Object> measure(@PathVariable String name) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        return slos.measure(name);
    }

    // ------------------------------------------------------------------ 事故

    @PostMapping("/incidents")
    public Map<String, Object> openIncident(@RequestBody Map<String, Object> document) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        return slos.openIncident(document, subject.id());
    }

    /** 从最近的未抑制异常自动开事故（同一主资产只开一个）。 */
    @PostMapping("/incidents/from-anomalies")
    public Map<String, Object> openFromAnomalies(@RequestBody(required = false) Map<String, Object> body) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        Map<String, Object> input = body == null ? Map.of() : body;
        Integer minutes = intOf(input.get("minutes"));
        return slos.openFromAnomalies(minutes == null ? 1440 : minutes, subject.id());
    }

    @GetMapping("/incidents")
    public Map<String, Object> listIncidents(@RequestParam(required = false) String status,
                                             @RequestParam(defaultValue = "50") int limit) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        List<Map<String, Object>> rows = slos.listIncidents(status, limit);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", rows.size());
        payload.put("incidents", rows);
        return payload;
    }

    @GetMapping("/incidents/{id}")
    public Map<String, Object> incident(@PathVariable long id) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        return slos.incident(id);
    }

    @PostMapping("/incidents/{id}/events")
    public Map<String, Object> addEvent(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        Map<String, Object> input = body == null ? Map.of() : body;
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = input.get("detail") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        return slos.addEvent(id, str(input.get("eventType")) == null ? "NOTE" : str(input.get("eventType")),
                str(input.get("message")), subject.id(), detail);
    }

    /**
     * 解决事故。
     *
     * <p>必须二选一：关联沉淀出的规则，或说明为什么不需要新规则。
     * 这不是形式主义 —— 没有这一步，事故处理完什么都不会改进。
     */
    @PostMapping("/incidents/{id}/resolve")
    public Map<String, Object> resolve(@PathVariable long id,
                                       @RequestBody(required = false) Map<String, Object> body) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        Map<String, Object> input = body == null ? Map.of() : body;
        @SuppressWarnings("unchecked")
        Map<String, Object> postmortem = input.get("postmortem") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        return slos.resolve(id, subject.id(), str(input.get("closedLoopRuleUrn")),
                str(input.get("noRuleNeededReason")), postmortem);
    }

    /** 运维总览：事故分布、MTTR、SLO 达成、以及"没有沉淀规则"的事故数。 */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        Map<String, Object> payload = new LinkedHashMap<>(slos.overview());
        payload.put("anomaly", anomalies.overview());
        return payload;
    }

    // ------------------------------------------------------------------ 工具

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Double num(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intOf(Object value) {
        Double parsed = num(value);
        return parsed == null ? null : parsed.intValue();
    }
}
