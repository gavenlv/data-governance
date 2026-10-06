package com.datagovernance.api.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.api.security.Subjects;
import com.datagovernance.ingestion.datasource.DataSourceService;
import com.datagovernance.policy.AccessPolicy;
import com.datagovernance.policy.Subject;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据源管理（docs/09 §9.1）。
 *
 * <p>为什么需要它：{@code /api/v1/collect/run} 要求每次提供完整 DSN（含口令），
 * 于是口令被反复抄写、落进脚本 —— 这比不加密更糟。保存连接后，
 * 扫描与测试都只引用 {@code dataSourceId}，口令只以密文存在于一个地方。
 *
 * <p>权限刻意与"能读资产"分开：连接信息（主机、端口、账号）属于采集运维面，
 * 因此 READER 完全不可见，STEWARD 只读，只有具备采集权限的 EDITOR（与 ADMIN）
 * 能增删改与触发扫描。
 *
 * <p>本控制器**从不回显凭据**：响应里只有 {@code hasCredentials} 与已脱敏的 endpoint。
 */
@RestController
@RequestMapping("/api/v1/datasources")
public class DataSourceController {

    private final DataSourceService dataSources;

    public DataSourceController(DataSourceService dataSources) {
        this.dataSources = dataSources;
    }

    /** 数据源列表（供界面表格；列表中不含任何凭据）。 */
    @GetMapping
    public Map<String, Object> list() {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "datasource:read");
        Map<String, Object> payload = ApiExceptionHandler.list("dataSources", dataSources.list());
        payload.put("cipher", dataSources.cipherStatus());
        payload.put("note", "endpoint 已脱敏（去掉 user:password@ 与 ?password= 一类参数）；"
                + "凭据只以 AES-256-GCM 密文存储，且密钥不在库内");
        return payload;
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "datasource:read");
        return dataSources.get(id);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody DataSourceService.SaveRequest request) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "datasource:write");
        return withHint(dataSources.create(request, subject.id()));
    }

    /**
     * 编辑。**未提供的凭据字段保持原值** —— 改命名空间或只轮换口令时，
     * 不必重新抄一遍完整 DSN 与账号。
     */
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id,
                                      @RequestBody DataSourceService.SaveRequest request) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "datasource:write");
        return withHint(dataSources.update(id, request, subject.id()));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "datasource:write");
        return dataSources.delete(id);
    }

    /** 连接测试：只读探测，不写元数据；结论不通时返回 200 + ok=false。 */
    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable String id) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "datasource:write");
        return dataSources.test(id);
    }

    /** 一键扫描：用保存的连接跑一次完整采集（护栏/快照/运行记录全部照旧）。 */
    @PostMapping("/{id}/scan")
    public Map<String, Object> scan(@PathVariable String id) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "datasource:write");
        Map<String, Object> payload = new LinkedHashMap<>(dataSources.scan(id, subject.id()));
        payload.put("hint", "运行记录可用 runId 在 GET /api/v1/collect/runs 查询；"
                + "失败批次可用 POST /api/v1/collect/rollback?runId=... 整体回退");
        return payload;
    }

    private static Map<String, Object> withHint(Map<String, Object> payload) {
        payload.put("note", List.of("凭据已加密存储（AES-256-GCM），密钥来自环境变量 DG_SECRET_KEY 且不在库内",
                "响应不回显凭据：仅告知 hasCredentials",
                "编辑时未填写的凭据字段保持原值，无需重新输入完整连接细节"));
        return payload;
    }
}