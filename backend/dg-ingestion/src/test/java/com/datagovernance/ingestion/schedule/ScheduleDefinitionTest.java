package com.datagovernance.ingestion.schedule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 调度定义校验、DSN 解析与口令遮蔽。 */
class ScheduleDefinitionTest {

    private static Map<String, Object> base() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", "demo");
        raw.put("source", "postgres");
        raw.put("dsn", "postgresql://user:secret@host:5432/db");
        return raw;
    }

    @Test
    void defaultsAreApplied() {
        ScheduleDefinition definition = ScheduleDefinition.fromMap(base());
        assertEquals("prod", definition.namespace());
        assertEquals("0 3 * * *", definition.cron());
        assertEquals("Asia/Shanghai", definition.timezone());
        assertTrue(definition.enabled());
        assertEquals(200, definition.guardConfig().maxDeletions());
    }

    /** 不支持的连接器必须**拒绝**，而不是先落库再在运行时失败。 */
    @Test
    void unsupportedSourceIsRejected() {
        Map<String, Object> raw = base();
        raw.put("source", "sqlite");
        ScheduleException error = assertThrows(ScheduleException.class,
                () -> ScheduleDefinition.fromMap(raw));
        assertTrue(error.getMessage().contains("sqlite"));
        assertTrue(error.getMessage().contains("postgres"));
    }

    @Test
    void missingNameOrDsnIsRejected() {
        Map<String, Object> noName = base();
        noName.remove("name");
        assertThrows(ScheduleException.class, () -> ScheduleDefinition.fromMap(noName));

        Map<String, Object> noDsn = base();
        noDsn.remove("dsn");
        assertThrows(ScheduleException.class, () -> ScheduleDefinition.fromMap(noDsn));
    }

    /** secret: 引用必须显式拒绝：假装支持 Vault 比不支持更危险。 */
    @Test
    void secretReferenceIsRejectedExplicitly() {
        Map<String, Object> raw = base();
        raw.put("dsn", "secret:kv/data/dg#dsn");
        ScheduleException error = assertThrows(ScheduleException.class,
                () -> ScheduleDefinition.fromMap(raw));
        assertTrue(error.getMessage().contains("Vault"));
        assertTrue(error.getMessage().contains("env:"));
    }

    /** env: 引用未设置时必须报错，绝不降级为默认值（静默连错库比报错严重）。 */
    @Test
    void unresolvedEnvReferenceFailsLoudly() {
        Map<String, Object> raw = base();
        raw.put("dsn", "env:DG_DEFINITELY_NOT_SET_VARIABLE");
        ScheduleDefinition definition = ScheduleDefinition.fromMap(raw);
        ScheduleException error = assertThrows(ScheduleException.class, definition::resolvedDsn);
        assertTrue(error.getMessage().contains("DG_DEFINITELY_NOT_SET_VARIABLE"));
    }

    @Test
    void inlineDsnIsReturnedAsIsButRedactedOnOutput() {
        ScheduleDefinition definition = ScheduleDefinition.fromMap(base());
        assertEquals("postgresql://user:secret@host:5432/db", definition.resolvedDsn());
        assertEquals("postgresql://user:***@host:5432/db", definition.toMap().get("dsn"));
    }

    @Test
    void envReferenceIsNotRedactedBecauseItHasNoSecret() {
        assertEquals("env:MY_DSN", ScheduleDefinition.redact("env:MY_DSN"));
    }

    @Test
    void guardOverridesAreParsed() {
        Map<String, Object> raw = base();
        raw.put("guard", Map.of("maxDeletions", 50, "maxDeleteRatio", 0.2, "minRetentionRatio", 0.8));
        ScheduleDefinition definition = ScheduleDefinition.fromMap(raw);
        assertEquals(50, definition.guardConfig().maxDeletions());
        assertEquals(0.2, definition.guardConfig().maxDeleteRatio(), 1e-9);
        assertEquals(0.8, definition.guardConfig().minRetentionRatio(), 1e-9);
    }

    @Test
    void schemasAndTablesAreCopiedNotShared() {
        Map<String, Object> raw = base();
        raw.put("schemas", List.of("public"));
        raw.put("tables", List.of("orders"));
        ScheduleDefinition definition = ScheduleDefinition.fromMap(raw);
        assertEquals(List.of("public"), definition.schemas());
        assertEquals(List.of("orders"), definition.tables());
        assertFalse(definition.cron().isEmpty());
    }

    /** 归一化后的 6 段表达式要出现在输出里：让使用者看到平台实际按什么调度。 */
    @Test
    void outputExposesNormalizedCron() {
        ScheduleDefinition definition = ScheduleDefinition.fromMap(base());
        assertEquals("0 0 3 * * *", definition.toMap().get("cronNormalized"));
    }
}
