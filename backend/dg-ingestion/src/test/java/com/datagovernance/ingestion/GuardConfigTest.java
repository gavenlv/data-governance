package com.datagovernance.ingestion;

import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 采集护栏测试（与 Python 参考实现的 17 项行为对齐）。
 *
 * <p>这是「防止一次错误采集清空目录」的最后一道防线，必须有完整的分支覆盖。
 */
class GuardConfigTest {

    private static Set<String> urns(int count, String prefix) {
        return IntStream.range(0, count).mapToObj(i -> prefix + i).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void firstRunNeverDeletes() {
        GuardConfig.GuardDecision decision = GuardConfig.defaults().evaluate(Set.of(), urns(3, "a"));

        assertThat(decision.blocked()).isFalse();
        assertThat(decision.deletions()).isEmpty();
        assertThat(decision.reasons().get(0)).contains("首次采集无基线");
    }

    @Test
    void noDeletionsProceeds() {
        GuardConfig.GuardDecision decision =
                GuardConfig.defaults().evaluate(urns(10, "a"), urns(12, "a"));

        assertThat(decision.blocked()).isFalse();
        assertThat(decision.deletions()).isEmpty();
    }

    @Test
    void smallDeletionWithinThresholdProceeds() {
        Set<String> previous = urns(100, "t");
        Set<String> current = urns(95, "t"); // 删除 5 个 = 5%

        GuardConfig.GuardDecision decision = GuardConfig.defaults().evaluate(previous, current);

        assertThat(decision.blocked()).isFalse();
        assertThat(decision.deletions()).hasSize(5);
        assertThat(decision.reasons()).anyMatch(reason -> reason.contains("在阈值内"));
    }

    @Test
    void exceedingMaxDeletionsBlocks() {
        Set<String> previous = urns(1000, "t");
        Set<String> current = urns(700, "t"); // 删除 300 > 上限 200

        GuardConfig.GuardDecision decision =
                new GuardConfig(200, 0.99, 0.10, 10).evaluate(previous, current);

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.reasons()).anyMatch(reason -> reason.contains("超过上限"));
    }

    @Test
    void exceedingDeleteRatioBlocks() {
        Set<String> previous = urns(100, "t");
        Set<String> current = urns(60, "t"); // 40% > 30%

        GuardConfig.GuardDecision decision = GuardConfig.defaults().evaluate(previous, current);

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.reasons()).anyMatch(reason -> reason.contains("删除比例"));
    }

    @Test
    void massDisappearanceBlocksOnRetention() {
        // 25 张表删到 5 张：保留率 20% << 70%
        GuardConfig.GuardDecision decision =
                GuardConfig.defaults().evaluate(urns(25, "tbl_"), urns(5, "tbl_"));

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.reasons()).anyMatch(reason -> reason.contains("实体数骤降"));
        assertThat(decision.deletions()).hasSize(20);
    }

    @Test
    void smallBaselineSkipsRatioRules() {
        // 小目录的比例噪音大（3 个里删 1 个就是 33%），不应触发拦截
        GuardConfig.GuardDecision decision =
                GuardConfig.defaults().evaluate(Set.of("a", "b", "c"), Set.of("a"));

        assertThat(decision.blocked()).isFalse();
    }

    @Test
    void metricsAreExposedForObservability() {
        GuardConfig.GuardDecision decision =
                GuardConfig.defaults().evaluate(urns(100, "t"), urns(90, "t"));

        assertThat(decision.previousCount()).isEqualTo(100);
        assertThat(decision.currentCount()).isEqualTo(90);
        assertThat(decision.deleteRatio()).isCloseTo(0.10, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(decision.retentionRatio()).isCloseTo(0.90, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void configIsSerialisableForApiAndUi() {
        assertThat(GuardConfig.defaults().asMap())
                .containsEntry("maxDeletions", 200)
                .containsEntry("maxDeleteRatio", 0.30)
                .containsEntry("minRetentionRatio", 0.70);
    }
}
