package com.datagovernance.lineage;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * SQL 静态解析侧车客户端（docs/10 §2）。
 *
 * <p>设计依据：sqlglot 的方言覆盖（20+ 方言、内置列级血缘）远强于 JVM 侧现成方案，
 * 因此按文档选型把「SQL 解析」放在 <b>Python 侧车</b>，Java 控制面通过内部 HTTP 调用。
 * 侧车只解析，不落库；写入血缘图与登记失败样本都在控制面完成
 * （{@link SqlParseService}）——保证只有一个写入者、只有一个真相源。
 *
 * <p>侧车不可用时抛 {@link SidecarUnavailable}，由 API 层转成 502 并标注原因：
 * 空血缘与"解析没跑"必须可区分（docs/09 §9.2）。
 *
 * <p>注：本模块按 Spring Boot 3.1 选型，使用 {@link RestTemplate}
 * （{@code RestClient} 自 Spring Framework 6.1 / Boot 3.2 起才提供）。
 */
@Component
public class SqlParseSidecarClient {

    private static final Logger log = LoggerFactory.getLogger(SqlParseSidecarClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public SqlParseSidecarClient(
            @Value("${dg.lineage.sidecar-url:}") String baseUrl,
            @Value("${dg.lineage.sidecar-timeout-seconds:10}") long timeoutSeconds) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeoutMillis = (int) Duration.ofSeconds(timeoutSeconds).toMillis();
        factory.setConnectTimeout(timeoutMillis);
        factory.setReadTimeout(timeoutMillis);
        this.restTemplate = new RestTemplate(factory);
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** 侧车是否已配置并可达。未配置 = false（不尝试请求）。 */
    public boolean available() {
        if (baseUrl.isEmpty()) {
            return false;
        }
        try {
            restTemplate.getForEntity(baseUrl + "/healthz", String.class);
            return true;
        } catch (RuntimeException e) {
            log.debug("SQL 解析侧车不可达：{}", e.getMessage());
            return false;
        }
    }

    /** 侧车健康信息（探活 + sqlglot 版本 + 支持方言数）。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> health() {
        if (baseUrl.isEmpty()) {
            throw new SidecarUnavailable("SQL 解析侧车未配置（dg.lineage.sidecar-url 为空）");
        }
        try {
            Map<String, Object> body = restTemplate.getForObject(baseUrl + "/healthz", Map.class);
            return body == null ? Map.of() : body;
        } catch (RuntimeException e) {
            throw new SidecarUnavailable("SQL 解析侧车不可达：" + e.getMessage());
        }
    }

    /**
     * 解析 SQL 得到结构化结果（多语句）。
     *
     * @throws SidecarUnavailable 侧车不可用（调用方应据此在响应中标注，而非返回空血缘）
     */
    public ParseResponse parseStatements(String sql, String dialect, String namespace,
                                         String defaultSchema) {
        if (baseUrl.isEmpty()) {
            throw new SidecarUnavailable("SQL 解析侧车未配置（dg.lineage.sidecar-url 为空）；"
                    + "启动方式：python -m dg.cli sidecar");
        }
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("sql", sql);
            body.put("dialect", dialect == null ? "hive" : dialect);
            body.put("namespace", namespace == null ? "prod" : namespace);
            if (defaultSchema != null) {
                body.put("defaultSchema", defaultSchema);
            }
            String response = restTemplate.postForObject(
                    baseUrl + "/api/v1/lineage/parse", body, String.class);
            if (response == null) {
                throw new SidecarUnavailable("SQL 解析侧车返回空响应");
            }
            return mapper.readValue(response, ParseResponse.class);
        } catch (SidecarUnavailable e) {
            throw e;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // 4xx = 调用方的问题（例如方言不支持），**不是**侧车不可用。
            // 把两者混在一起会让使用者去排查一个根本没坏的服务。
            throw new ParseRejected("SQL 解析侧车拒绝了请求（HTTP " + e.getRawStatusCode() + "）："
                    + extractDetail(e.getResponseBodyAsString()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new SidecarUnavailable("SQL 解析侧车响应无法解析：" + e.getOriginalMessage());
        } catch (RuntimeException e) {
            throw new SidecarUnavailable("SQL 解析侧车调用失败：" + e.getMessage());
        }
    }

    /** 从侧车的错误体里取出可读的 detail（FastAPI 的 {"detail": "..."}）。 */
    private static String extractDetail(String body) {
        if (body == null || body.isBlank()) {
            return "无错误详情";
        }
        try {
            Map<String, Object> parsed = new ObjectMapper().readValue(body, Map.class);
            Object detail = parsed.get("detail");
            return detail == null ? body : String.valueOf(detail);
        } catch (Exception e) {
            return body;
        }
    }

    /** 侧车返回体。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParseResponse(String dialect, String sqlglotVersion, int statements,
                                int failed, int downgraded, List<ParseStatement> results) {
    }

    /** 单条语句的解析结果。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParseStatement(String sql, String dialect, String statementType, String targetTable,
                                 List<String> targetColumns, List<String> sourceTables,
                                 String parseLevel, String error, List<String> warnings,
                                 List<ColumnEdge> columnEdges) {
    }

    /** 一条列级血缘边（侧车语义：from=上游，to=下游）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ColumnEdge(String fromTable, String fromColumn, String toTable, String toColumn,
                             String transform, String expression, String dependencyKind,
                             String parseLevel, double confidence, String cardinality) {
    }

    /** 侧车不可用。 */
    public static class SidecarUnavailable extends RuntimeException {
        public SidecarUnavailable(String message) {
            super(message);
        }
    }

    /**
     * 侧车明确拒绝了请求（HTTP 4xx，例如方言不支持）。
     *
     * <p>与 {@link SidecarUnavailable} 分开是刻意的：把"你传错了"报成"服务坏了"
     * 会让人去排查一个没坏的服务。
     */
    public static class ParseRejected extends RuntimeException {
        public ParseRejected(String message) {
            super(message);
        }
    }
}
