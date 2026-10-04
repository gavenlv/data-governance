package com.datagovernance.policy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 访问路由与 SLA 测试。
 *
 * <p>路由决定了"谁能批别人的数据权限"，是治理体系里最不能靠试错验证的一段逻辑：
 * 太宽松 → 分级高的数据被低权限的人批了；太严格 → 没人能批，流程僵死。
 */
class AccessRoutingTest {

    @Test
    void highClassificationRequiresStewardCountersign() {
        AccessRouting.Route route = AccessRouting.route("L4", "DATASET", "月度经营分析需要", 90, "analyst@corp");
        assertTrue(route.approvers().contains("STEWARD"));
        assertTrue(route.notes().stream().anyMatch(note -> note.contains("会签")),
                "高分级必须要求会签，而不是只由 Owner 单点批准");
    }

    @Test
    void lowClassificationRoutesToStewardOrOwner() {
        AccessRouting.Route route = AccessRouting.route("L1", "DATASET", "对外公开数据核对", 30, "analyst@corp");
        assertEquals(List.of("STEWARD"), route.approvers());
        assertTrue(route.notes().stream().anyMatch(note -> note.contains("L1")));
    }

    /** 列级申请风险小 → SLA 更宽；整表申请风险大 → SLA 更紧。 */
    @Test
    void slaDependsOnGranularity() {
        assertEquals(72, AccessRouting.route("L2", "COLUMN", "做用户画像特征", 60, "a@corp").slaHours());
        assertEquals(48, AccessRouting.route("L2", "DATASET", "做用户画像特征", 60, "a@corp").slaHours());
    }

    /** 最小粒度建议：整表申请要明确提示"给列不给表"。 */
    @Test
    void wholeTableRequestGetsGranularitySuggestion() {
        AccessRouting.Route route = AccessRouting.route("L2", "DATASET", "取数分析用", 90, "a@corp");
        assertTrue(route.minGranularitySuggestion().contains("列级申请"));
        AccessRouting.Route column = AccessRouting.route("L2", "COLUMN", "取数分析用", 90, "a@corp");
        assertTrue(column.minGranularitySuggestion().contains("最小粒度"));
    }

    @Test
    void shortPurposeAndLongDurationAreFlagged() {
        AccessRouting.Route vague = AccessRouting.route("L2", "DATASET", "看", 90, "a@corp");
        assertTrue(vague.notes().stream().anyMatch(note -> note.contains("用途描述过短")));

        AccessRouting.Route forever = AccessRouting.route("L2", "DATASET", "日常经营分析使用", 730, "a@corp");
        assertTrue(forever.notes().stream().anyMatch(note -> note.contains("超过一年")),
                "长期需求应走角色授权而不是单次申请");
    }

    /** 自批自用必须被拒绝。 */
    @Test
    void requesterCannotApproveOwnRequest() {
        List<String> problems = AccessRouting.authorizeDecision(
                List.of("STEWARD"), List.of("STEWARD"), "analyst@corp", "analyst@corp");
        assertTrue(problems.stream().anyMatch(p -> p.contains("自批自用")), problems.toString());
    }

    @Test
    void approverOutsideTheChainIsRejected() {
        List<String> problems = AccessRouting.authorizeDecision(
                List.of("READER"), List.of("STEWARD", "ADMIN"), "analyst@corp", "reader@corp");
        assertTrue(problems.stream().anyMatch(p -> p.contains("不在审批链")), problems.toString());
    }

    @Test
    void adminCanAlwaysDecideAndValidApproverPasses() {
        assertTrue(AccessRouting.authorizeDecision(List.of("ADMIN"), List.of("STEWARD"),
                "analyst@corp", "admin@corp").isEmpty());
        assertTrue(AccessRouting.authorizeDecision(List.of("STEWARD"), List.of("STEWARD"),
                "analyst@corp", "steward@corp").isEmpty());
    }

    @Test
    void expiryIsBounded() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        assertEquals(Instant.parse("2026-01-31T00:00:00Z"), AccessRouting.expiresAt(now, 30));
        // 期限缺失 → 默认 90 天；超大值被硬上限（3650 天）截断 —— 不允许事实上的永久授权
        assertEquals(Instant.parse("2026-04-01T00:00:00Z"), AccessRouting.expiresAt(now, null));
        assertEquals(AccessRouting.expiresAt(now, 3650), AccessRouting.expiresAt(now, 100000),
                "超过硬上限的期限必须被截断到上限，而不是原样接受");
        assertTrue(AccessRouting.expiresAt(now, 100000).isBefore(now.plus(java.time.Duration.ofDays(3660))));
    }

    /**
     * 复核建议：**没有使用数据时必须返回 NEED_MORE_INFO**，
     * 而不是默认"看起来没被用过"→ 那会导致在用的权限被误回收。
     */
    @Test
    void reviewWithoutUsageDataNeverAssumesUnused() {
        AccessRouting.ReviewSuggestion suggestion = AccessRouting.reviewSuggestion(400, null, "90d");
        assertEquals("NEED_MORE_INFO", suggestion.decision());
        assertTrue(suggestion.reason().contains("不假设"), suggestion.reason());
    }

    @Test
    void reviewWithUsageDataGivesActionableDecision() {
        assertEquals("REVOKE", AccessRouting.reviewSuggestion(120, false, "90d").decision());
        assertEquals("KEEP", AccessRouting.reviewSuggestion(30, false, "90d").decision());
        assertEquals("KEEP", AccessRouting.reviewSuggestion(400, true, "90d").decision());
    }
}
