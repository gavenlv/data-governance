package com.datagovernance.ingestion.schedule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

/**
 * 从 YAML 载入调度定义（"治理即代码"：调度定义进 Git，再 apply 落库）。
 *
 * <p>对应 {@code config/schedules.example.yaml} 的格式：
 * <pre>
 * schedules:
 *   - name: dg-self-postgres
 *     source: postgres
 *     dsn: env:DG_SOURCE_DSN
 *     schemas: [public]
 *     cron: "0 3 * * *"
 * </pre>
 *
 * <p>载入阶段<b>只解析不落库</b>，校验交给 {@link ScheduleDefinition#validate()}，
 * 因此"格式错"与"语义错"的报错能分开看。
 */
public final class ScheduleYamlLoader {

    private ScheduleYamlLoader() {
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> load(Path path) {
        if (path == null || !Files.exists(path)) {
            throw new ScheduleException("调度文件不存在：" + path);
        }
        String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ScheduleException("读取调度文件失败：" + e.getMessage());
        }
        Object parsed = new Yaml().load(content);
        if (parsed == null) {
            return List.of();
        }
        if (!(parsed instanceof Map<?, ?> document)) {
            throw new ScheduleException("调度文件顶层应为映射（含 schedules 键）：" + path);
        }
        Object entries = document.get("schedules");
        if (entries == null) {
            return List.of();
        }
        if (!(entries instanceof List<?> list)) {
            throw new ScheduleException("schedules 必须是数组：" + path);
        }
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new ScheduleException("schedules 的每一项必须是映射：" + path);
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            map.forEach((key, value) -> entry.put(String.valueOf(key), value));
            out.add(entry);
        }
        return out;
    }
}
