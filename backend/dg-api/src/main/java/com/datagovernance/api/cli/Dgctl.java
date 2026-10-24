package com.datagovernance.api.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.datagovernance.sdk.client.DgApiException;
import com.datagovernance.sdk.client.DgClient;
import com.datagovernance.sdk.client.DgClient.DgClientException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code dgctl}：与 HTTP API 一致的命令行（docs/09 §9.10）。
 *
 * <p>放在控制面 jar 里而不是另外打一个 CLI 包，是刻意的：
 * <ul>
 *   <li>运维只需要分发**一个**产物（`java -jar dg-api.jar dgctl ...`），
 *       不会出现"CLI 版本与服务端 API 不匹配"这类只在故障时暴露的问题；</li>
 *   <li>CLI 走的是**同一个** {@link DgClient}，与 SDK 共用传输与鉴权逻辑，
 *       因此"CLI 能做的事"和"SDK 能做的事"不会漂移。</li>
 * </ul>
 *
 * <p>**CLI 不启动 Spring 容器**：它在 {@code main} 里被优先处理，不连数据库、不建 Web 服务、
 * 不跑迁移。一条诊断命令不该因为数据库连不上而失败 —— 那恰恰是它要诊断的场景。
 *
 * <p>退出码语义（脚本可直接依赖）：
 * <pre>
 *   0  成功
 *   1  平台返回业务错误（401/403/404/422/5xx）
 *   2  用法错误（命令/参数不对）
 *   3  连接失败（地址错、服务没起、网络不通）
 * </pre>
 */
public final class Dgctl {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Dgctl() {
    }

    public static int run(String[] args) {
        forceUtf8Console();
        try {
            return execute(args);
        } catch (DgApiException e) {
            System.err.println("错误：" + e);
            return 1;
        } catch (DgClientException e) {
            System.err.println("连接失败：" + e.getMessage());
            System.err.println("提示：确认控制面已在运行（--url 或 DG_API_URL），并检查网络/端口");
            return 3;
        } catch (IllegalArgumentException e) {
            System.err.println("用法错误：" + e.getMessage());
            usage();
            return 2;
        }
    }

    /**
     * 把 stdout/stderr 固定为 UTF-8。
     *
     * <p>Windows 上 Java 的 {@code stdout.encoding} 默认跟随本地代码页（中文环境是 GBK），
     * 于是同一个命令在控制台、CI 日志、被脚本捕获三种场景下编码各不相同 ——
     * 断言中文输出的脚本会因此随机失败。CLI 的输出必须**跨平台确定**，
     * 所以这里显式固定 UTF-8；控制台若仍是旧代码页，可用 {@code chcp 65001}
     * 或改用 {@code --json}（机器可读路径）。
     */
    private static void forceUtf8Console() {
        try {
            System.setOut(new java.io.PrintStream(
                    new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
                    java.nio.charset.StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(
                    new java.io.FileOutputStream(java.io.FileDescriptor.err), true,
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException ignored) {
            // 拿不到标准输出流时保持默认，不影响命令本身
        }
    }

    private static int execute(String[] rawArgs) {
        Options options = Options.parse(rawArgs);
        if (options.command == null) {
            usage();
            return 2;
        }
        DgClient client = DgClient.builder()
                .baseUrl(options.url)
                .token(options.token)
                .build();

        return switch (options.command) {
            case "doctor" -> doctor(client, options);
            case "capabilities" -> capabilities(client, options);
            case "search" -> search(client, options);
            case "lineage" -> lineage(client, options);
            case "asset" -> asset(client, options);
            case "impact" -> impact(client, options);
            case "collect-health" -> collectHealth(client, options);
            case "version", "--version", "-v" -> {
                System.out.println("dgctl（Java 控制面）" + " 0.1.0");
                yield 0;
            }
            case "help", "--help", "-h" -> {
                usage();
                yield 0;
            }
            default -> {
                System.err.println("未知命令：" + options.command);
                usage();
                yield 2;
            }
        };
    }

    // ------------------------------------------------------------------ 命令

    /** 体检：配置 + 连通性 + 认证 + 关键接口。 */
    private static int doctor(DgClient client, Options options) {
        System.out.println("平台地址：" + options.url);
        System.out.println("携带令牌：" + (options.token == null || options.token.isBlank() ? "否（多数接口会 401）" : "是"));
        DgClient.Capabilities capabilities = client.capabilities();
        System.out.printf("认证与连通：OK（能力清单可读，共 %d 项：已实现 %d / 部分 %d / 未实现 %d）%n",
                capabilities.total(), capabilities.implemented(),
                capabilities.partial(), capabilities.notImplemented());
        JsonNode health = client.collectHealth();
        System.out.println("采集健康度：OK（" + health.path("sources").size() + " 个数据源）");
        List<DgClient.Capability> missing = capabilities.byStatus("NOT_IMPLEMENTED");
        if (!missing.isEmpty()) {
            System.out.println("未实现的能力：" + missing.stream().map(DgClient.Capability::id).toList());
        }
        System.out.println("结论：平台可用。");
        return 0;
    }

    private static int capabilities(DgClient client, Options options) {
        DgClient.Capabilities capabilities = client.capabilities();
        if (options.json) {
            pretty(client.getJson("/api/v1/capabilities"));
            return 0;
        }
        System.out.printf("能力 %d 项：已实现 %d / 部分 %d / 未实现 %d%n",
                capabilities.total(), capabilities.implemented(),
                capabilities.partial(), capabilities.notImplemented());
        for (DgClient.Capability item : capabilities.items()) {
            if (options.domain != null && !options.domain.equalsIgnoreCase(item.domain())) {
                continue;
            }
            System.out.printf("  %-34s %-14s %s%n", item.id(), item.status(), item.name());
        }
        return 0;
    }

    private static int search(DgClient client, Options options) {
        String keyword = options.positional(0);
        if (keyword == null) {
            System.err.println("用法：dgctl search <关键词> [--limit N]");
            return 2;
        }
        DgClient.SearchResult result = client.search(keyword, options.limit);
        if (options.json) {
            pretty(client.getJson("/api/v1/search?q=" + urlEncode(keyword) + "&limit=" + options.limit));
            return 0;
        }
        System.out.printf("「%s」命中 %d 条（服务端已按可见性前置过滤）%n", result.query(), result.count());
        result.results().forEach(hit -> System.out.printf("  %-14s %-40s %s%n",
                hit.entityType(), hit.displayName(), hit.urn()));
        return 0;
    }

    private static int lineage(DgClient client, Options options) {
        String urn = options.positional(0);
        if (urn == null) {
            System.err.println("用法：dgctl lineage <urn> [--direction downstream|upstream] [--depth N]");
            return 2;
        }
        DgClient.LineageGraph graph = client.lineage(urn, options.direction, options.depth);
        if (options.json) {
            pretty(client.getJson("/api/v1/lineage/graph?urn=" + urlEncode(urn)
                    + "&direction=" + options.direction + "&depth=" + options.depth));
            return 0;
        }
        System.out.printf("%s（方向 %s，深度 %d）%n", graph.urn(), graph.direction(), graph.maxDepth());
        System.out.println("节点：" + (graph.nodes() == null ? 0 : graph.nodes().size()));
        if (graph.nodes() != null) {
            graph.nodes().forEach(node -> System.out.printf("  depth=%d %s%n", node.depth(), node.urn()));
        }
        if (graph.edges() != null && graph.edges().isArray() && !graph.edges().isEmpty()) {
            System.out.println("边：" + graph.edges().size());
        } else {
            System.out.println("边：0（该深度内没有血缘边，或都被可见性/置信度过滤）");
        }
        return 0;
    }

    private static int asset(DgClient client, Options options) {
        String urn = options.positional(0);
        if (urn == null) {
            System.err.println("用法：dgctl asset <urn>");
            return 2;
        }
        JsonNode detail = client.asset(urn);
        if (options.json) {
            pretty(detail);
            return 0;
        }
        System.out.println("URN：" + detail.path("urn").asText(urn));
        System.out.println("类型：" + detail.path("entityType").asText(detail.path("entity_type").asText("-")));
        JsonNode aspects = detail.path("aspects");
        if (aspects.isObject()) {
            System.out.println("Aspect：" + String.join(", ", iterable(aspects.fieldNames())));
        }
        if (detail.hasNonNull("lifecycle")) {
            System.out.println("生命周期：" + detail.path("lifecycle").asText());
        }
        return 0;
    }

    private static int impact(DgClient client, Options options) {
        String urn = options.positional(0);
        if (urn == null) {
            System.err.println("用法：dgctl impact <urn> [--depth N]");
            return 2;
        }
        JsonNode impact = client.impact(urn, options.depth);
        if (options.json) {
            pretty(impact);
            return 0;
        }
        JsonNode affected = impact.path("affected");
        System.out.println("影响面：" + (affected.isArray() ? affected.size() : 0) + " 个下游资产");
        if (affected.isArray()) {
            affected.forEach(item -> System.out.printf("  score=%.3f %s%n",
                    item.path("score").asDouble(), item.path("urn").asText()));
        }
        if (impact.hasNonNull("truncated") && impact.path("truncated").asBoolean()) {
            System.out.println("注意：深度边界处仍有下游，真实影响面更大（truncated=true）");
        }
        return 0;
    }

    private static int collectHealth(DgClient client, Options options) {
        JsonNode health = client.collectHealth();
        pretty(health);
        return 0;
    }

    // ------------------------------------------------------------------ 辅助

    private static void pretty(JsonNode node) {
        try {
            System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node));
        } catch (Exception e) {
            System.out.println(node);
        }
    }

    private static List<String> iterable(java.util.Iterator<String> names) {
        List<String> list = new ArrayList<>();
        names.forEachRemaining(list::add);
        return list;
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void usage() {
        System.out.println("""
                dgctl —— 数据治理平台命令行（与 HTTP API 一致）

                用法：
                  java -jar dg-api-0.1.0.jar dgctl <命令> [参数] [--url URL] [--token TOKEN] [--json]

                命令：
                  doctor                          体检：配置 / 连通性 / 认证 / 关键接口
                  capabilities [--domain D]       能力清单（平台唯一的"做完了哪些"真相源）
                  search <关键词> [--limit N]     全文检索（服务端前置授权过滤）
                  lineage <urn> [--depth N]       血缘图（--direction downstream|upstream）
                  asset <urn>                     资产详情
                  impact <urn> [--depth N]        影响分析 / 爆炸半径
                  collect-health                  采集健康度

                环境变量：
                  DG_API_URL    默认 http://127.0.0.1:8081
                  DG_API_TOKEN  令牌（也可用 --token）

                退出码：0 成功 / 1 平台错误 / 2 用法错误 / 3 连接失败
                """);
    }

    /** 极简参数解析：只支持长选项 + 位置参数。 */
    private static final class Options {
        private String command;
        private final List<String> positional = new ArrayList<>();
        private String url = env("DG_API_URL", "http://127.0.0.1:8081");
        private String token = env("DG_API_TOKEN", null);
        private boolean json;
        private int limit = 20;
        private int depth = 3;
        private String direction = "downstream";
        private String domain;

        static Options parse(String[] args) {
            Options options = new Options();
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                switch (argument) {
                    case "--url", "-u" -> options.url = next(args, ++index, argument);
                    case "--token", "-t" -> options.token = next(args, ++index, argument);
                    case "--json" -> options.json = true;
                    case "--limit" -> options.limit = Integer.parseInt(next(args, ++index, argument));
                    case "--depth" -> options.depth = Integer.parseInt(next(args, ++index, argument));
                    case "--direction" -> options.direction = next(args, ++index, argument);
                    case "--domain" -> options.domain = next(args, ++index, argument);
                    default -> {
                        if (argument.equals("--version") || argument.equals("-v")
                                || argument.equals("--help") || argument.equals("-h")) {
                            if (options.command == null) {
                                options.command = argument;
                                continue;
                            }
                        }
                        if (argument.startsWith("-")) {
                            throw new IllegalArgumentException("未知选项：" + argument);
                        }
                        if (options.command == null) {
                            options.command = argument;
                        } else {
                            options.positional.add(argument);
                        }
                    }
                }
            }
            return options;
        }

        String positional(int index) {
            return index < positional.size() ? positional.get(index) : null;
        }

        private static String next(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("选项缺少取值：" + option);
            }
            return args[index];
        }

        private static String env(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value;
        }
    }

    /** 供测试：不经过 main 也能取用法文本。 */
    static String usageText() {
        return String.join("\n", Arrays.asList("doctor", "capabilities", "search", "lineage",
                "asset", "impact", "collect-health"));
    }

    static Map<String, String> exitCodes() {
        return Map.of("ok", "0", "api-error", "1", "usage", "2", "connection", "3");
    }
}
