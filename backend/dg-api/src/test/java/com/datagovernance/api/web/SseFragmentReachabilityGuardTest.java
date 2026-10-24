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
 * 守卫测试：让 CVE-2026-47890（SSE + 视图 fragment 的流损坏，CRITICAL）的"打不到"**可验证**。
 *
 * <p>背景：{@code org.springframework:spring-webmvc} 6.2.19（Spring Boot 3.5.16 托管）
 * 存在 CRITICAL 漏洞，修复只出现在 Spring Framework 7.0.9（= Spring Boot 4.x）。
 * 我们按 NFR-SEC-01 登记了带到期日的豁免（{@code security/dependency-waivers.yaml}），
 * 而豁免成立的前提是：**本平台不使用 Server-Sent Events**。
 *
 * <p>这个前提完全可能被一个善意的改动破坏 —— "给前端加一个进度推送"是个很自然的需求，
 * 加完之后豁免就从"有论证的例外"变成"一条过期文档"。因此这里把前提钉死：
 * 一旦出现 SSE / 流式响应 API，测试立刻失败，要求重新评估豁免（要么不引入，
 * 要么隔离，要么等 Boot 4 迁移）。
 *
 * <p>与 {@link XsltViewReachabilityGuardTest} 一样，本测试**自带反向自检**：
 * 用合成代码证明检测逻辑真的会红，否则它只是一个永远通过的装饰。
 */
class SseFragmentReachabilityGuardTest {

    /** 与流式响应相关的标识（出现即说明前提可能被破坏）。 */
    private static final List<String> SSE_MARKERS = List.of(
            "SseEmitter",
            "ResponseBodyEmitter",
            "StreamingResponseBody",
            "text/event-stream");

    /** 允许在注释里讨论这些 API 的显式标记（避免"写注释解释为什么不用"反而让守卫变红）。 */
    private static final String ESCAPE_MARKER = "sse-guard-ok";

    @Test
    void noStreamingResponseUsageAnywhere() throws IOException {
        Path backend = locateBackendDir();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(backend)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (!file.toString().contains("src" + java.io.File.separator + "main")) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file);
                for (int index = 0; index < lines.size(); index++) {
                    String line = lines.get(index);
                    if (line.contains(ESCAPE_MARKER)) {
                        continue;
                    }
                    for (String marker : SSE_MARKERS) {
                        if (line.contains(marker)) {
                            violations.add(backend.relativize(file) + ":" + (index + 1)
                                    + " 出现 " + marker + " → " + line.trim());
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "检测到 SSE / 流式响应相关代码：CVE-2026-47890 的豁免前提（不使用流式响应）已被破坏。"
                        + "此时必须重新评估豁免（不引入 / 隔离 / 等 Boot 4 迁移），"
                        + "而不是把这条豁免的到期日往后一推了事：\n  " + String.join("\n  ", violations));
    }

    @Test
    void waiverFileStillRegistersThisAdvisory() throws IOException {
        Path waiver = locateWaiver();
        String text = Files.readString(waiver);
        assertTrue(text.contains("GHSA-j9f9-w8pj-32f8"),
                "豁免文件里没有 GHSA-j9f9-w8pj-32f8：若无修复版本又无豁免，审计门禁会阻断构建");
        assertTrue(text.contains("expires:"), "豁免必须带到期日，否则等于永久豁免");
    }

    /** 反向自检：证明这条守卫真的会失败（否则它只是一个永远绿灯的装饰）。 */
    @Test
    void guardActuallyDetectsTheDangerousPattern() {
        assertFalse(detectStreamingUsage("private final SseEmitter emitter = new SseEmitter();").isEmpty(),
                "守卫没有检出 SseEmitter");
        assertFalse(detectStreamingUsage("return new ResponseBodyEmitter();").isEmpty(),
                "守卫没有检出 ResponseBodyEmitter");
        assertFalse(detectStreamingUsage("response.setContentType(\"text/event-stream\");").isEmpty(),
                "守卫没有检出 text/event-stream");
        assertTrue(detectStreamingUsage("return ResponseEntity.ok(payload);").isEmpty(),
                "守卫把普通 JSON 响应误判成流式响应（误报会让守卫被绕过）");
        assertTrue(detectStreamingUsage("// 不使用 SseEmitter：见 sse-guard-ok").isEmpty(),
                "带 sse-guard-ok 的注释行应被跳过，否则守卫会逼人删掉解释");
    }

    private static List<String> detectStreamingUsage(String line) {
        List<String> hits = new ArrayList<>();
        if (line.contains(ESCAPE_MARKER)) {
            return hits;
        }
        for (String marker : SSE_MARKERS) {
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

    private static Path locateWaiver() {
        for (Path candidate = Path.of("").toAbsolutePath(); candidate != null; candidate = candidate.getParent()) {
            Path file = candidate.resolve("security").resolve("dependency-waivers.yaml");
            if (Files.exists(file)) {
                return file;
            }
        }
        throw new IllegalStateException("找不到 security/dependency-waivers.yaml");
    }
}
