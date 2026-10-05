package com.datagovernance.core.sql;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归门禁：禁止把 <b>数组值</b> 直接交给 {@code JdbcTemplate} 去绑 {@code text[]} 列。
 *
 * <p><b>为什么值得写一条"扫描源码"的测试</b>：这个缺陷在项目里出现过 5 次
 * （调度 DSN、访问申请的 permissions/approvers、质量规则、Edge Agent 的 capabilities、
 * 引擎审计的 columns），每一次都是"编译通过、单测通过、跑起来 500"。
 * 根因是 {@code JdbcTemplate} 对 {@code String[]} 是**假接受**：要到 {@code setObject}
 * 才抛 {@code SQLFeatureNotSupportedException}。靠"记住这件事"显然不管用 ——
 * 那就把它变成一条会失败的测试。
 *
 * <p>要区分两种 {@code .toArray()}，否则会满屏误报：
 * <ul>
 *   <li><b>安全</b>：{@code List<Object> params} → {@code jdbc.queryForList(sql, params.toArray())}，
 *       这只是把变参列表摊开，与数组列无关；</li>
 *   <li><b>危险</b>：{@code jdbc.update(sql, values.toArray())}，其中 {@code values} 是
 *       {@code List<String>}/{@code String[]}，会被 pgjdbc 当成数组值 → 运行期异常。</li>
 * </ul>
 * 因此实现按"接收者的声明类型"判断，而不是按写法判断。
 * 确需绑定数组请用 {@link TextArrays#of}；行尾加注释 {@code pg-array-ok} 可显式豁免。
 */
class PgArrayBindingGuardTest {

    /** 被摊开成变参的"参数容器"声明（这些变量名下的 toArray() 是安全的）。 */
    private static final Pattern VARARGS_DECLARATION = Pattern.compile(
            "(?:List\\s*<\\s*Object\\s*>|Object\\s*\\[\\s*\\])\\s+(\\w+)");

    private static final Pattern RECEIVER = Pattern.compile("(?:(\\w+)\\s*\\.\\s*)?toArray\\(\\)");

    private static final List<String> JDBC_CALLS = List.of(
            "jdbc.update(", "jdbc.queryForList(", "jdbc.queryForObject(", "jdbc.query(");

    @Test
    void noRawArrayBindingInRealSources() throws IOException {
        List<String> violations = detectViolations(locateBackendDir());
        assertTrue(violations.isEmpty(),
                "发现把**数组值**直接交给 JdbcTemplate 的写法（pgjdbc 不支持，会在执行期抛 "
                        + "SQLFeatureNotSupportedException）：\n  " + String.join("\n  ", violations)
                        + "\n请改用 com.datagovernance.core.sql.TextArrays.of(connection, values) + "
                        + "PreparedStatementCreator（参见 EngineAuditService / AccessRequestService 的写法）。");
    }

    /**
     * 反向自检：证明这条门禁**真的能抓到**它要抓的写法。
     *
     * <p>只写"扫描源码"的检查而不验证它有效，等于给了一个"永远不会失败的绿灯" ——
     * 与供应链扫描里那条纪律一样：检查器本身必须有反向用例。
     */
    @Test
    void guardActuallyDetectsTheDangerousPattern() {
        String bad = "        jdbc.update(\"INSERT ... VALUES (?)\", rules.stream().map(r -> r.id()).toArray());";
        assertFalse(detectInSource("Sample.java", List.of(bad)).isEmpty(),
                "门禁没有抓到危险写法，说明它已经失效");

        String safe = """
                List<Object> params = new ArrayList<>();
                params.add("x");
                return jdbc.queryForList(sql.toString(), params.toArray());
                """;
        assertTrue(detectInSource("Sample.java", safe.lines().toList()).isEmpty(),
                "把变参列表误判成了数组绑定（误报会让门禁被绕过）");
    }

    // ------------------------------------------------------------------ 实现

    private static List<String> detectViolations(Path backend) throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(backend)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (!file.toString().contains("src" + java.io.File.separator + "main")) {
                    continue;
                }
                violations.addAll(detectInSource(backend.relativize(file).toString(),
                        Files.readAllLines(file)));
            }
        }
        return violations;
    }

    private static List<String> detectInSource(String name, List<String> lines) {
        Set<String> varargsContainers = new HashSet<>();
        for (String line : lines) {
            Matcher declaration = VARARGS_DECLARATION.matcher(line);
            while (declaration.find()) {
                varargsContainers.add(declaration.group(1));
            }
        }
        List<String> violations = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.contains("pg-array-ok") || !line.contains(".toArray()")) {
                continue;
            }
            if (JDBC_CALLS.stream().noneMatch(line::contains)) {
                continue;
            }
            Matcher receiver = RECEIVER.matcher(line);
            while (receiver.find()) {
                String holder = receiver.group(1);
                if (holder != null && varargsContainers.contains(holder)) {
                    continue;   // 变参列表摊开：安全
                }
                violations.add(name + ":" + (index + 1) + " → " + line.trim());
            }
        }
        return violations;
    }

    /** 从当前模块目录向上找仓库根的 backend 目录。 */
    private static Path locateBackendDir() {
        for (Path candidate = Path.of("").toAbsolutePath(); candidate != null; candidate = candidate.getParent()) {
            Path backend = candidate.resolve("backend");
            if (Files.isDirectory(backend) && Files.isDirectory(backend.resolve("dg-core"))) {
                return backend;
            }
            Path nested = candidate.resolve("..").resolve("backend").normalize();
            if (Files.isDirectory(nested) && Files.isDirectory(nested.resolve("dg-core"))) {
                return nested;
            }
        }
        throw new IllegalStateException("找不到 backend 目录，无法执行数组绑定门禁");
    }
}
