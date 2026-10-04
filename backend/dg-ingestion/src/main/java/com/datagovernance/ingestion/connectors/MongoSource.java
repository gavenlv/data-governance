package com.datagovernance.ingestion.connectors;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.datagovernance.ingestion.RawModels;
import com.datagovernance.ingestion.Source;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

/**
 * MongoDB 连接器（docs/09 §9.1）。
 *
 * <p>MongoDB 是<b>无 schema 的</b>，因此连接器的核心任务不是"读结构"而是<b>推断结构</b>。
 * 推断规则（必须显式写下来，否则不同人实现会得到完全不同的"表结构"）：
 * <ul>
 *   <li>每个集合采样最多 {@code sampleSize} 条文档（默认 200），**取并集**而不是只看第一条 ——
 *       只看第一条会得到一个"看起来很美但漏字段"的结构，比没有结构更危险；</li>
 *   <li>嵌套对象下钻为点号路径（{@code address.city}），深度上限防止递归爆炸；</li>
 *   <li>数组记为 {@code array<元素类型>}；元素类型不一致时记为 {@code array<mixed>}；</li>
 *   <li>同一字段在不同文档里类型不同 → 记为 {@code mixed(typeA|typeB)} 而不是取其中一个：
 *       "字段类型不稳定"本身就是最该被看到的治理信号；</li>
 *   <li>{@code _id} 作为主键（MongoDB 的天然唯一键）。</li>
 * </ul>
 *
 * <p>数量口径：集合的文档数用 {@code estimatedDocumentCount()}（基于元数据，**估算值**），
 * 不写成"精确行数"——与 docs/09 §9.4 的精度标注纪律一致。
 */
public class MongoSource implements Source {

    private static final int DEFAULT_SAMPLE = 200;
    private static final int MAX_DEPTH = 4;

    private final String uri;
    private final List<String> includeDatabases;
    private final List<String> includeCollections;
    private final int sampleSize;

    public MongoSource(String uri, List<String> includeDatabases, List<String> includeCollections,
                       Integer sampleSize) {
        this.uri = uri;
        this.includeDatabases = includeDatabases == null ? List.of() : includeDatabases;
        this.includeCollections = includeCollections == null ? List.of() : includeCollections;
        this.sampleSize = sampleSize == null || sampleSize < 1 ? DEFAULT_SAMPLE : Math.min(sampleSize, 5000);
    }

    /** 从 DSN 构造：{@code mongodb://user:pass@host:27017/?authSource=admin}。 */
    public static MongoSource fromDsn(String dsn, List<String> databases, List<String> collections,
                                      Integer sampleSize) {
        if (dsn == null || dsn.isBlank()) {
            throw new IllegalStateException("缺少 MongoDB DSN：期望 mongodb://user:pass@host:27017/");
        }
        String value = dsn.trim();
        if (value.contains("://")
                && !value.startsWith("mongodb://") && !value.startsWith("mongodb+srv://")) {
            // 显式拒绝其它协议：把 `mysql://host/db` 自动补成 `mongodb://mysql://host/db`
            // 会得到一个语法错误极难理解的连接失败
            throw new IllegalStateException("MongoDB DSN 的协议必须是 mongodb:// 或 mongodb+srv://，收到 "
                    + value.substring(0, value.indexOf("://")) + "://");
        }
        if (!value.startsWith("mongodb://") && !value.startsWith("mongodb+srv://")) {
            // 容忍只写 host:port 的简写，但补全后仍走标准连接串解析
            value = "mongodb://" + value;
        }
        return new MongoSource(value, databases, collections, sampleSize);
    }

    @Override
    public String name() {
        return "mongodb";
    }

    @Override
    public String platform() {
        return "mongodb";
    }

    @Override
    public Stream<RawModels.RawDataset> extract() {
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri))
                .build();
        List<RawModels.RawDataset> datasets = new ArrayList<>();
        try (MongoClient client = MongoClients.create(settings)) {
            for (String databaseName : resolveDatabases(client)) {
                MongoDatabase database = client.getDatabase(databaseName);
                for (String collectionName : database.listCollectionNames()) {
                    if (!includeCollections.isEmpty() && !includeCollections.contains(collectionName)) {
                        continue;
                    }
                    datasets.add(describe(database, databaseName, collectionName));
                }
            }
        }
        return datasets.stream();
    }

    private List<String> resolveDatabases(MongoClient client) {
        if (!includeDatabases.isEmpty()) {
            return includeDatabases;
        }
        List<String> names = new ArrayList<>();
        client.listDatabaseNames().forEach(names::add);
        names.removeAll(Set.of("admin", "local", "config"));
        return names;
    }

    private RawModels.RawDataset describe(MongoDatabase database, String databaseName,
                                          String collectionName) {
        MongoCollection<Document> collection = database.getCollection(collectionName);
        // 字段路径 → 出现次数 + 观察到的类型集合
        Map<String, FieldStat> stats = new LinkedHashMap<>();
        int sampled = 0;
        for (Document document : collection.find().limit(sampleSize)) {
            sampled++;
            walk(document, "", 0, stats);
        }

        List<RawModels.RawColumn> columns = new ArrayList<>();
        int ordinal = 1;
        for (Map.Entry<String, FieldStat> entry : stats.entrySet()) {
            FieldStat stat = entry.getValue();
            columns.add(new RawModels.RawColumn(
                    entry.getKey(),
                    stat.typeSummary(),
                    // 可空的两种来源都要算：① 部分文档没有这个字段；② 见过 null 值。
                    // 只看 ① 会把 `email: null` 这种真实可空字段标成不可空
                    MongoTypeInference.nullable(stat.count, sampled, stat.sawNull),
                    ordinal++,
                    stat.coverageNote(sampled),
                    null));
        }
        columns.sort(Comparator.comparingInt(RawModels.RawColumn::ordinal));

        long estimated = collection.estimatedDocumentCount();
        String comment = "MongoDB 集合；采样 %d 条文档推断结构；文档数≈%d（estimateDocumentCount，估算值）"
                .formatted(sampled, estimated);
        return new RawModels.RawDataset(
                platform(), databaseName, databaseName, collectionName,
                "COLLECTION", comment, columns, List.of("_id"), List.of());
    }

    private void walk(Document document, String prefix, int depth, Map<String, FieldStat> stats) {
        if (depth > MAX_DEPTH) {
            return;
        }
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Document nested) {
                // 先记录嵌套对象本身（便于看到"这里有个子文档"），再下钻
                stats.computeIfAbsent(path, k -> new FieldStat()).observe("object");
                walk(nested, path, depth + 1, stats);
            } else if (value instanceof List<?> list) {
                stats.computeIfAbsent(path, k -> new FieldStat()).observe(MongoTypeInference.arrayType(list));
            } else {
                stats.computeIfAbsent(path, k -> new FieldStat()).observe(MongoTypeInference.scalarType(value));
            }
        }
    }

    /**
     * 一个字段路径的统计（出现次数 + 类型集合）。
     *
     * <p>推断规则本身在 {@link MongoTypeInference} 里（纯函数、有单测），
     * 这里只负责收集观察结果 —— 规则与收集分离，规则才能被断言锁住。
     */
    private static final class FieldStat {
        private int count;
        private boolean sawNull;
        private final Set<String> types = new LinkedHashSet<>();

        void observe(String type) {
            count++;
            if ("null".equals(type)) {
                sawNull = true;
                return;
            }
            types.add(type);
        }

        String typeSummary() {
            return MongoTypeInference.summarize(types, sawNull);
        }

        String coverageNote(int sampled) {
            return MongoTypeInference.coverageNote(count, sampled, sawNull);
        }
    }
}
