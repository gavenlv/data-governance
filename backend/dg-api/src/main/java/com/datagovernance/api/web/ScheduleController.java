package com.datagovernance.api.web;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.api.security.Subjects;
import com.datagovernance.ingestion.schedule.ScheduleService;
import com.datagovernance.ingestion.schedule.ScheduleYamlLoader;
import com.datagovernance.policy.AccessPolicy;
import com.datagovernance.policy.Subject;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 采集调度（"治理即代码"：YAML 进 Git → apply 落库 → 常驻调度执行）。
 *
 * <p>权限：读取调度需 {@code model:read}（与 Python 参考实现一致）；
 * 变更调度需 {@code schedule:write}；手动触发需 {@code collect:run}。
 */
@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleController {

    private final ScheduleService schedules;

    public ScheduleController(ScheduleService schedules) {
        this.schedules = schedules;
    }

    @GetMapping
    public Map<String, Object> list() {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "model:read");
        List<Map<String, Object>> rows = schedules.list();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", rows.size());
        payload.put("schedules", rows);
        payload.put("scheduler", Map.of(
                "mode", "in-process（dg-ingestion ScheduleRunner）",
                "mutex", "pg_try_advisory_lock（随连接释放，崩溃不留死锁；不用 Redlock）",
                "note", "多副本同时轮询是安全的：互斥下沉到数据库"));
        return payload;
    }

    /** 应用一批调度定义（JSON 形态，等价于 YAML 的 schedules 数组）。 */
    @PostMapping
    public Map<String, Object> apply(@RequestBody Map<String, Object> document) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "schedule:write");

        Object entries = document.get("schedules");
        if (!(entries instanceof List<?> list)) {
            throw new IllegalArgumentException("请求体必须含 schedules 数组（与 config/schedules.example.yaml 同构）");
        }
        List<Map<String, Object>> raw = list.stream()
                .map(item -> {
                    if (!(item instanceof Map<?, ?> map)) {
                        throw new IllegalArgumentException("schedules 的每一项必须是对象");
                    }
                    Map<String, Object> entry = new LinkedHashMap<>();
                    map.forEach((key, value) -> entry.put(String.valueOf(key), value));
                    return entry;
                })
                .toList();
        return schedules.apply(raw);
    }

    /** 从 YAML 文件应用调度定义（治理即代码的主路径）。 */
    @PostMapping("/apply-file")
    public Map<String, Object> applyFile(@RequestParam String path) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "schedule:write");
        Map<String, Object> payload = schedules.apply(ScheduleYamlLoader.load(Path.of(path)));
        payload.put("source", path);
        return payload;
    }

    /** 手动触发一次（用于验证配置，不改变下次自动执行时间）。 */
    @PostMapping("/{name}/run")
    public Map<String, Object> run(@PathVariable String name,
                                   @RequestParam(defaultValue = "false") boolean acceptDeletions) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "collect:run");
        return schedules.run(name, acceptDeletions, subject.id());
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> delete(@PathVariable String name) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "schedule:write");
        boolean removed = schedules.delete(name);
        if (!removed) {
            throw new com.datagovernance.core.MetadataException.NotFound("调度不存在：" + name);
        }
        return Map.of("deleted", name);
    }
}
