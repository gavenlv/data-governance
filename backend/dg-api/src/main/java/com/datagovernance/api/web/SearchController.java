package com.datagovernance.api.web;

import java.util.LinkedHashMap;
import java.util.Map;

import com.datagovernance.api.security.Subjects;
import com.datagovernance.core.search.SearchIndexConsumer;
import com.datagovernance.core.search.SearchService;
import com.datagovernance.policy.AccessPolicy;
import com.datagovernance.policy.Subject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 检索与检索索引（docs/09 §9.3）。
 *
 * <p>检索走**前置**授权过滤：可见范围条件在查询前注入（{@link AccessPolicy#visibilityFilter}），
 * 因此返回的总数与分面统计都只反映"你可见的范围"，不会泄露无权资产的存在性与规模。
 */
@RestController
@RequestMapping("/api/v1")
public class SearchController {

    private final SearchService search;
    private final SearchIndexConsumer consumer;

    public SearchController(SearchService search, SearchIndexConsumer consumer) {
        this.search = search;
        this.consumer = consumer;
    }

    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(name = "type", required = false) String entityType,
            @RequestParam(required = false) String platform,
            @RequestParam(defaultValue = "20") int limit) {

        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");

        SearchService.SearchResult result = search.search(q, entityType, platform, limit,
                AccessPolicy.visibleLevels(subject));
        Map<String, Object> payload = result.asMap();
        if (result.count() == 0 && !result.query().isEmpty()) {
            // 空结果必须给出"最接近的候选"，而不是一句"没有结果"（docs/09 §9.3）
            payload.put("suggestions", search.suggestions(q, AccessPolicy.visibleLevels(subject)));
            payload.put("note", "无结果：已给出最接近的候选。若确实缺失，请提资产登记需求（这是需求信号，不是空白页）");
        }
        return payload;
    }

    /** 索引水位：界面据此显示"元数据已更新，索引同步中"。 */
    @GetMapping("/index/lag")
    public Map<String, Object> lag() {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        return consumer.lag();
    }

    /** 增量消费一次（批处理/排障用；常驻消费由 IndexMaintenanceJob 负责）。 */
    @PostMapping("/index/consume")
    public Map<String, Object> consume(@RequestParam(defaultValue = "500") int batchSize,
                                       @RequestParam(required = false) Integer maxBatches) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "index:consume");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stats", consumer.consume(batchSize, maxBatches).asMap());
        payload.put("lag", consumer.lag());
        return payload;
    }

    /**
     * 索引重建：清空派生视图 → 从 seq=0 重放。
     *
     * <p>这是"派生视图可丢弃、可重放重建"（ADR-002）的可执行证明 ——
     * 不是文档里的一句主张，而是一个可以随时跑、跑完结果一致的接口。
     */
    @PostMapping("/index/rebuild")
    public Map<String, Object> rebuild(@RequestParam(defaultValue = "500") int batchSize) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "index:rebuild");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stats", consumer.rebuild(batchSize).asMap());
        payload.put("lag", consumer.lag());
        return payload;
    }

    /** 重置消费者进度（重放起点）。 */
    @PostMapping("/index/reset")
    public Map<String, Object> reset() {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "index:rebuild");
        consumer.reset();
        return consumer.lag();
    }
}
