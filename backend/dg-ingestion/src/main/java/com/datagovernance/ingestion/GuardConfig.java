package com.datagovernance.ingestion;

import java.util.List;

/**
 * 采集护栏（docs/09 §9.1）。
 *
 * <p>为什么必须有它：采集配置改错、账号权限变化、源端短暂不可用，都会让「采集结果」
 * 看起来像「大量资产被删除」。没有护栏，一次错误采集就会把目录清空。
 *
 * <p>四条规则（任一触发即 BLOCKED，本轮不执行任何删除）：
 * <ol>
 *   <li>最大删除数</li>
 *   <li>最大删除比例</li>
 *   <li>实体数保留率</li>
 *   <li>首次采集不判删除（无基线可比）</li>
 * </ol>
 *
 * <p>被拦截时**不更新基线**，保证修好配置后仍能检出同样的异常。
 */
public record GuardConfig(
        int maxDeletions,
        double maxDeleteRatio,
        double minRetentionRatio,
        int ratioMinBaseline) {

    public static GuardConfig defaults() {
        return new GuardConfig(200, 0.30, 0.70, 10);
    }

    public record GuardDecision(
            boolean blocked,
            List<String> reasons,
            List<String> deletions,
            int previousCount,
            int currentCount) {

        public GuardDecision {
            reasons = List.copyOf(reasons);
            deletions = List.copyOf(deletions);
        }

        public double deleteRatio() {
            return previousCount == 0 ? 0.0 : (double) deletions.size() / previousCount;
        }

        public double retentionRatio() {
            return previousCount == 0 ? 1.0 : (double) currentCount / previousCount;
        }

        public String blockReason() {
            return blocked ? String.join("; ", reasons) : null;
        }
    }

    /** 纯函数，便于覆盖所有触发分支。 */
    public GuardDecision evaluate(java.util.Set<String> previous, java.util.Set<String> current) {
        java.util.List<String> deletions = previous.stream()
                .filter(urn -> !current.contains(urn))
                .sorted()
                .toList();
        java.util.List<String> reasons = new java.util.ArrayList<>();
        boolean blocked = false;

        if (previous.isEmpty()) {
            // 首次采集（无基线）：绝不判删除，否则会把「部分采集」当「全部消失」
            return new GuardDecision(false, List.of("首次采集无基线，跳过删除检测"),
                    List.of(), 0, current.size());
        }
        if (deletions.isEmpty()) {
            return new GuardDecision(false, List.of(), List.of(), previous.size(), current.size());
        }

        if (deletions.size() > maxDeletions) {
            blocked = true;
            reasons.add("待删实体数 %d 超过上限 %d".formatted(deletions.size(), maxDeletions));
        }
        double deleteRatio = (double) deletions.size() / previous.size();
        if (previous.size() >= ratioMinBaseline && deleteRatio > maxDeleteRatio) {
            blocked = true;
            reasons.add("删除比例 %.1f%% 超过阈值 %.0f%%（%d/%d）".formatted(
                    deleteRatio * 100, maxDeleteRatio * 100, deletions.size(), previous.size()));
        }
        double retention = (double) current.size() / previous.size();
        if (previous.size() >= ratioMinBaseline && retention < minRetentionRatio) {
            blocked = true;
            reasons.add("实体数骤降：本轮见到 %d，上轮 %d，保留率 %.1f%% 低于阈值 %.0f%%".formatted(
                    current.size(), previous.size(), retention * 100, minRetentionRatio * 100));
        }
        if (!blocked) {
            reasons.add("删除 %d 个实体（比例 %.1f%%）在阈值内，执行软删".formatted(
                    deletions.size(), deleteRatio * 100));
        }
        return new GuardDecision(blocked, reasons, deletions, previous.size(), current.size());
    }

    /** 结构化摘要，供 API 与界面展示。 */
    public java.util.Map<String, Object> asMap() {
        return java.util.Map.of(
                "maxDeletions", maxDeletions,
                "maxDeleteRatio", maxDeleteRatio,
                "minRetentionRatio", minRetentionRatio,
                "ratioMinBaseline", ratioMinBaseline);
    }
}
