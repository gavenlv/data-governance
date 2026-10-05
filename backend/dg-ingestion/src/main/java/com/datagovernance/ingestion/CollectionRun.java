package com.datagovernance.ingestion;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 一次采集运行的结果（docs/09 §9.1 的「采集自身的可观测性」）。 */
public record CollectionRun(
        String runId,
        String source,
        String namespace,
        String scope,
        String status,
        String blockReason,
        int datasetsSeen,
        int datasetsCreated,
        int schemasWritten,
        int schemasUnchanged,
        int descriptionsWritten,
        int deletedCandidates,
        int deleted,
        int columnsSeen,
        /** BI 资产（仪表板）数量：与数据集分开计数，因为它们的护栏语义不同。 */
        int dashboardsSeen,
        int dashboardsCreated,
        int dashboardsDeleted,
        boolean dashboardGuardBlocked,
        String dashboardGuardReason,
        /** 连接器自带血缘写入的边数（如 dbt manifest 的 depends_on）。 */
        int edgesWritten,
        /** 血缘跳过说明（解析不到 URN 的边）：必须可见，否则会让人以为血缘已经全了。 */
        List<String> edgeSkipNotes,
        List<String> errors,
        long durationMs,
        Instant startedAt,
        Instant finishedAt,
        GuardConfig.GuardDecision guard) {

    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String BLOCKED = "BLOCKED";
    public static final String FAILED = "FAILED";

    public CollectionRun {
        errors = List.copyOf(errors == null ? List.of() : errors);
        edgeSkipNotes = List.copyOf(edgeSkipNotes == null ? List.of() : edgeSkipNotes);
    }

    public boolean ok() {
        return SUCCEEDED.equals(status) && errors.isEmpty();
    }

    public Map<String, Object> asMap() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("runId", runId);
        out.put("source", source);
        out.put("namespace", namespace);
        out.put("scope", scope);
        out.put("status", status);
        if (blockReason != null) {
            out.put("blockReason", blockReason);
        }
        out.put("datasetsSeen", datasetsSeen);
        out.put("datasetsCreated", datasetsCreated);
        out.put("schemasWritten", schemasWritten);
        out.put("schemasUnchanged", schemasUnchanged);
        out.put("descriptionsWritten", descriptionsWritten);
        out.put("deletedCandidates", deletedCandidates);
        out.put("deleted", deleted);
        out.put("columnsSeen", columnsSeen);
        out.put("dashboardsSeen", dashboardsSeen);
        out.put("dashboardsCreated", dashboardsCreated);
        out.put("dashboardsDeleted", dashboardsDeleted);
        if (dashboardGuardBlocked) {
            out.put("dashboardGuardBlocked", true);
            out.put("dashboardGuardReason", dashboardGuardReason);
        }
        out.put("edgesWritten", edgesWritten);
        if (!edgeSkipNotes.isEmpty()) {
            out.put("edgeSkipNotes", edgeSkipNotes);
        }
        out.put("errors", errors);
        out.put("durationMs", durationMs);
        out.put("startedAt", startedAt == null ? null : startedAt.toString());
        out.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
        return out;
    }
}
