package com.datagovernance.api.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫测试：让 CVE-2026-47884（XsltView 路径限制，CRITICAL）的"打不到"**可验证**。
 *
 * <p>背景：{@code org.springframework:spring-webmvc} 6.2.19（Spring Boot 3.5.16 托管）
 * 存在 CRITICAL 漏洞，而修复只出现在 Spring Framework 7.0.9（= Spring Boot 4.x）。
 * 在 6.2.x 线拿到修复版本之前，我们按 NFR-SEC-01 登记了**带到期日的豁免**
 * （{@code security/dependency-waivers.yaml}）—— 而豁免的前提是"这个漏洞在我们这里打不到"。
 *
 * <p>口头承诺会过期，测试不会。公告给的两个前提条件是：
 * <ol>
 *   <li>应用使用 {@code XsltView}；</li>
 *   <li>存在 {@code /**} 映射且**视图名未被显式指定**。</li>
 * </ol>
 * 本测试把这两条都钉死：任何一条被破坏（有人引入 XSLT 视图、或去掉显式视图名），
 * 这里立刻失败，从而提醒"豁免的前提已经不成立"。
 */
class XsltViewReachabilityGuardTest {

    /** 与 XsltView 相关的类/配置名（出现即说明前提 ① 可能被破坏）。 */
    private static final List<String> XSLT_MARKERS = List.of(
            "XsltView", "XmlViewResolver", "XSLT", "javax.xml.transform", "TransformerFactory");

    @Test
    void noXsltViewUsageAnywhere() throws IOException {
        Path backend = locateBackendDir();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(backend)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (!file.toString().contains("src" + java.io.File.separator + "main")) {
                    continue;
                }
                // 守卫测试自身会提到这些名字（注释/常量），因此跳过测试目录已在上面完成
                List<String> lines = Files.readAllLines(file);
                for (int index = 0; index < lines.size(); index++) {
                    String line = lines.get(index);
                    if (line.contains("xslt-guard-ok")) {
                        continue;
                    }
                    for (String marker : XSLT_MARKERS) {
                        if (line.contains(marker)) {
                            violations.add(backend.relativize(file) + ":" + (index + 1)
                                    + " 出现 " + marker + " → " + line.trim());
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "检测到 XSLT 视图相关代码：CVE-2026-47884 的豁免前提（不使用 XsltView）已被破坏，"
                        + "此时必须重新评估豁免（要么修复、要么隔离）：\n  " + String.join("\n  ", violations));
    }

    @Test
    void everyViewControllerSpecifiesViewNameExplicitly() throws IOException {
        Path backend = locateBackendDir();
        Path webConfig = null;
        try (Stream<Path> files = Files.walk(backend)) {
            webConfig = files.filter(path -> path.getFileName().toString().equals("WebConfig.java"))
                    .findFirst().orElse(null);
        }
        assertTrue(webConfig != null, "找不到 WebConfig.java");
        List<String> lines = Files.readAllLines(webConfig);

        int registrations = 0;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!line.contains("addViewController(")) {
                continue;
            }
            registrations++;
            // 显式视图名可能写在后续 1–2 行（链式调用换行），因此在 3 行窗口内检查
            String window = String.join(" ", lines.subList(index, Math.min(index + 3, lines.size())));
            assertTrue(window.contains("setViewName("),
                    "第 " + (index + 1) + " 行的 addViewController 没有显式 setViewName："
                            + "CVE-2026-47884 的第二个前提（视图名未显式指定）就此成立。"
                            + "当前代码片段：" + window.trim());
        }
        assertTrue(registrations > 0, "WebConfig 里应当有视图控制器注册（SPA 回退）");
    }

    /** 反向自检：证明这条守卫真的会失败（否则它只是一个永远绿灯的装饰）。 */
    @Test
    void guardActuallyDetectsTheDangerousPattern() {
        assertTrue(detectXsltUsage("import org.springframework.web.servlet.view.xslt.XsltView;").contains("XsltView"),
                "守卫没有检出 XsltView 引用");
        assertTrue(detectXsltUsage("var resolver = new XmlViewResolver();").contains("XmlViewResolver"),
                "守卫没有检出 XmlViewResolver");
        assertTrue(detectXsltUsage("private final String name = \"xslt\";").isEmpty(),
                "守卫把无关代码误判成 XSLT 使用（误报会让守卫被绕过）");
    }

    private static List<String> detectXsltUsage(String line) {
        List<String> hits = new ArrayList<>();
        for (String marker : XSLT_MARKERS) {
            if (line.contains(marker)) {
                hits.add(marker);
            }
        }
        return hits;
    }

    private static Path locateBackendDir() {
        for (Path candidate = Path.of("").toAbsolutePath(); candidate != null; candidate = candidate.getParent()) {
            Path backend = candidate.resolve("backend");
            if (Files.isDirectory(backend) && Files.isDirectory(backend.resolve("dg-api"))) {
                return backend;
            }
            Path nested = candidate.resolve("..").resolve("backend").normalize();
            if (Files.isDirectory(nested) && Files.isDirectory(nested.resolve("dg-api"))) {
                return nested;
            }
        }
        throw new IllegalStateException("找不到 backend 目录");
    }

    @Test
    void waiverFileStillCoversThisVulnerability() throws IOException {
        // 豁免到期或漏改 ID 时，审计工具会重新阻断；这里额外保证"豁免文件确实写了这一条"，
        // 避免有人删掉豁免却忘了补上修复
        Path waiver = null;
        for (Path candidate = Path.of("").toAbsolutePath(); candidate != null; candidate = candidate.getParent()) {
            Path file = candidate.resolve("security").resolve("dependency-waivers.yaml");
            if (Files.exists(file)) {
                waiver = file;
                break;
            }
        }
        assertTrue(waiver != null, "找不到 security/dependency-waivers.yaml");
        String text = Files.readString(waiver);
        assertTrue(text.contains("GHSA-pc63-qcmh-9cmg"),
                "豁免文件里没有 GHSA-pc63-qcmh-9cmg：若无修复版本又无豁免，审计门禁会阻断构建 —— "
                        + "要么补上豁免（含可达性论证与到期日），要么升级到已修复的版本");
        assertFalse(text.contains("expires: 2020"), "豁免到期日看起来是占位值");
    }
}
