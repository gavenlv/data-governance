package com.datagovernance.core.search;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.datagovernance.core.MetadataService;
import com.datagovernance.core.UrnUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 检索索引消费者（ADR-002 的落地，docs/09 §9.3）。
 *
 * <p><b>派生视图可丢弃、可重放重建</b>：{@code search_doc} 不是真相源，
 * 它完全由 {@code event_log} + 真相源重建。消费者进度记在 {@code consumer_offset}，
 * 支持从任意 seq 重放。
 *
 * <p>两条关键设计（都来自踩过的坑）：
 * <ol>
 *   <li><b>从真相源读当前值，而不是从事件 payload 拼装</b> —— 这样"乱序/重复消费"天然幂等；
 *       若从 payload 拼装，重放一次旧事件就会把索引回退到旧状态。</li>
 *   <li><b>每次写入记 {@code indexed_watermark}</b> —— 界面据此显示"元数据已更新，索引同步中"，
 *       而不是让用户面对一个静默滞后的搜索结果。</li>
 * </ol>
 */
@Component
public class SearchIndexConsumer {

    public static final String CONSUMER_NAME = "search_index";

    /** 进入检索索引的实体类型（Team/User 等组织实体不入检索索引）。 */
    private static final Set<String> INDEXED_TYPES = Set.of(
            "Platform", "Container", "Dataset", "Column", "Pipeline", "Dashboard", "GlossaryTerm",
            // Batch 2：契约与质量规则是一等实体，因此天然可被检索到（"哪个契约约束了这张表"）
            "DataContract", "QualityRule");

    private static final Logger log = LoggerFactory.getLogger(SearchIndexConsumer.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public SearchIndexConsumer(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------ 消费

    /** 增量消费事件流，维护 {@code search_doc}。 */
    public ConsumeStats consume(int batchSize) {
        return consume(batchSize, null);
    }

    /**
     * 增量消费事件流。
     *
     * @param maxBatches 最多处理多少批（null = 追到最新）；供常驻消费者避免长时间持锁
     */
    public ConsumeStats consume(int batchSize, Integer maxBatches) {
        int size = Math.max(1, Math.min(batchSize, 5000));
        int processed = 0;
        int indexed = 0;
        int deleted = 0;
        int batches = 0;
        long offset = currentOffset();
        long from = offset;

        while (true) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT seq, event_type, urn
                      FROM event_log
                     WHERE seq > ?
                     ORDER BY seq
                     LIMIT ?
                    """, offset, size);
            if (rows.isEmpty()) {
                break;
            }

            long batchEnd = offset;
            for (Map<String, Object> row : rows) {
                String eventType = String.valueOf(row.get("event_type"));
                String urn = String.valueOf(row.get("urn"));
                long seq = ((Number) row.get("seq")).longValue();

                ApplyResult result = apply(eventType, urn, seq);
                indexed += result.indexed();
                deleted += result.deleted();
                processed++;
                batchEnd = seq;
            }
            offset = batchEnd;
            setOffset(offset, "RUNNING");
            batches++;

            if (maxBatches != null && batches >= maxBatches) {
                break;
            }
        }

        setOffset(offset, "IDLE");
        return new ConsumeStats(processed, indexed, deleted, from, offset, batches);
    }

    /** 索引重建：清空索引 → 从 seq=0 重放（证明派生视图确实可丢弃）。 */
    public ConsumeStats rebuild(int batchSize) {
        tx.executeWithoutResult(status -> {
            jdbc.update("TRUNCATE search_doc");
            setOffset(0L, "RESET");
        });
        ConsumeStats stats = consume(batchSize, null);
        log.info("检索索引重建完成：处理 {} 事件，写入 {} 文档，删除 {}，seq {} → {}",
                stats.processed(), stats.indexed(), stats.deleted(), stats.fromSeq(), stats.toSeq());
        return stats;
    }

    /** 只重置消费者进度（配合 truncate 使用）。 */
    public void reset() {
        setOffset(0L, "RESET");
    }

    private record ApplyResult(int indexed, int deleted) {
    }

    private ApplyResult apply(String eventType, String urn, long watermark) {
        if (MetadataService.EVENT_ENTITY_DELETED.equals(eventType)) {
            jdbc.update("DELETE FROM search_doc WHERE urn = ?", urn);
            return new ApplyResult(0, 1);
        }
        if (!MetadataService.EVENT_ENTITY_CREATED.equals(eventType)
                && !MetadataService.EVENT_ASPECT_UPSERTED.equals(eventType)
                && !MetadataService.EVENT_ENTITY_RESURRECTED.equals(eventType)) {
            return new ApplyResult(0, 0);
        }

        String entityType;
        try {
            entityType = UrnUtils.parse(urn).entityType();
        } catch (RuntimeException e) {
            return new ApplyResult(0, 0);
        }
        if (!INDEXED_TYPES.contains(entityType)) {
            return new ApplyResult(0, 0);
        }

        boolean written = upsertDoc(urn, entityType, watermark);
        return new ApplyResult(written ? 1 : 0, written ? 0 : 1);
    }

    /**
     * 从真相源重建该资产的可检索文档。
     *
     * @return 是否写入了文档（false = 实体已不存在，已删除索引项）
     */
    private boolean upsertDoc(String urn, String entityType, long watermark) {
        List<Map<String, Object>> entities = jdbc.queryForList("""
                SELECT urn, entity_type, display_name, namespace
                  FROM entity WHERE urn = ? AND deleted_at IS NULL
                """, urn);
        if (entities.isEmpty()) {
            jdbc.update("DELETE FROM search_doc WHERE urn = ?", urn);
            return false;
        }
        Map<String, Object> entity = entities.get(0);

        Map<String, Map<String, Object>> aspects = new java.util.LinkedHashMap<>();
        jdbc.query("SELECT aspect_type, data FROM aspect WHERE urn = ?", rs -> {
            aspects.put(rs.getString("aspect_type"), parseJson(rs.getString("data")));
        }, urn);

        String[] parts = UrnUtils.parse(urn).parts().toArray(String[]::new);
        String platform = parts.length > 2 ? parts[1] : null;
        String container = parts.length > 2
                ? String.join(".", java.util.Arrays.copyOfRange(parts, 1, parts.length - 1))
                : null;

        String description = str(descText(aspects.get("descriptions")));
        List<String> owners = ownersOf(aspects.get("ownership"));
        List<String> tags = tagsOf(aspects.get("tags"));
        String classification = str(levelOf(aspects.get("classification")));

        String displayName = str(entity.get("display_name"));
        if (displayName == null || displayName.isBlank()) {
            displayName = parts[parts.length - 1];
        }
        String docText = IdentifierTokenizer.buildSearchText(displayName, urn, description, tags);

        final String finalDisplayName = displayName;
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO search_doc (urn, entity_type, display_name, namespace, platform, container,
                                            description, tags, owners, classification, tsv,
                                            indexed_at, indexed_watermark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, to_tsvector('simple', ?), now(), ?)
                    ON CONFLICT (urn) DO UPDATE
                        SET entity_type = EXCLUDED.entity_type,
                            display_name = EXCLUDED.display_name,
                            namespace = EXCLUDED.namespace,
                            platform = EXCLUDED.platform,
                            container = EXCLUDED.container,
                            description = EXCLUDED.description,
                            tags = EXCLUDED.tags,
                            owners = EXCLUDED.owners,
                            classification = EXCLUDED.classification,
                            tsv = EXCLUDED.tsv,
                            indexed_at = now(),
                            indexed_watermark = EXCLUDED.indexed_watermark
                    """);
            ps.setString(1, urn);
            ps.setString(2, String.valueOf(entity.get("entity_type")));
            ps.setString(3, finalDisplayName);
            ps.setString(4, str(entity.get("namespace")));
            ps.setString(5, platform);
            ps.setString(6, container);
            ps.setString(7, description);
            ps.setArray(8, connection.createArrayOf("text", tags.toArray()));
            ps.setArray(9, connection.createArrayOf("text", owners.toArray()));
            ps.setString(10, classification);
            ps.setString(11, docText);
            ps.setLong(12, watermark);
            return ps;
        });
        return true;
    }

    // ------------------------------------------------------------------ 观测

    /** 索引水位：界面据此提示"索引同步中"。 */
    public Map<String, Object> lag() {
        long lastConsumed = currentOffset();
        Long latest = jdbc.queryForObject("SELECT COALESCE(MAX(seq), 0) FROM event_log", Long.class);
        Integer docs = jdbc.queryForObject("SELECT COUNT(*) FROM search_doc", Integer.class);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("consumer", CONSUMER_NAME);
        payload.put("lastConsumedSeq", lastConsumed);
        payload.put("latestEventSeq", latest == null ? 0L : latest);
        payload.put("lag", (latest == null ? 0L : latest) - lastConsumed);
        payload.put("indexedDocs", docs == null ? 0 : docs);
        payload.put("status", status());
        return payload;
    }

    public long currentOffset() {
        List<Long> rows = jdbc.queryForList(
                "SELECT last_seq FROM consumer_offset WHERE consumer = ?", Long.class, CONSUMER_NAME);
        return rows.isEmpty() || rows.get(0) == null ? 0L : rows.get(0);
    }

    public String status() {
        List<String> rows = jdbc.queryForList(
                "SELECT status FROM consumer_offset WHERE consumer = ?", String.class, CONSUMER_NAME);
        return rows.isEmpty() ? "IDLE" : rows.get(0);
    }

    private void setOffset(long seq, String status) {
        jdbc.update("""
                INSERT INTO consumer_offset (consumer, last_seq, status, updated_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (consumer) DO UPDATE
                    SET last_seq = EXCLUDED.last_seq,
                        status = EXCLUDED.status,
                        updated_at = now()
                """, CONSUMER_NAME, seq, status);
    }

    // ------------------------------------------------------------- 小工具

    private static Map<String, Object> parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String descText(Map<String, Object> aspect) {
        return aspect == null ? null : str(aspect.get("text"));
    }

    private static String levelOf(Map<String, Object> aspect) {
        return aspect == null ? null : str(aspect.get("level"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> ownersOf(Map<String, Object> aspect) {
        if (aspect == null) {
            return List.of();
        }
        Object raw = aspect.get("owners");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> owners = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object value = map.get("urn") != null ? map.get("urn") : map.get("name");
                if (value != null) {
                    owners.add(String.valueOf(value));
                }
            } else if (item != null) {
                owners.add(String.valueOf(item));
            }
        }
        return owners;
    }

    @SuppressWarnings("unchecked")
    private static List<String> tagsOf(Map<String, Object> aspect) {
        if (aspect == null) {
            return List.of();
        }
        Object raw = aspect.get("tags");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> tags = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                tags.add(String.valueOf(item));
            }
        }
        return tags;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 一次消费的结果。 */
    public record ConsumeStats(int processed, int indexed, int deleted,
                               long fromSeq, long toSeq, int batches) {

        public Map<String, Object> asMap() {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("processed", processed);
            payload.put("indexed", indexed);
            payload.put("deleted", deleted);
            payload.put("fromSeq", fromSeq);
            payload.put("toSeq", toSeq);
            payload.put("batches", batches);
            return payload;
        }
    }
}
