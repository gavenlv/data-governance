package com.datagovernance.ingestion.schedule;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DSN 解析。
 *
 * <p>关键断言是"口令不能留在 URL 里"：它会被连接池、异常堆栈、日志打印出来，
 * 这是一条真实发生过的泄露路径。
 */
class JdbcTargetTest {

    @Test
    void postgresUrlIsConvertedToJdbc() {
        JdbcTarget target = JdbcTarget.parse("postgresql://alice:pw@db.host:25011/dg");
        assertEquals("jdbc:postgresql://db.host:25011/dg", target.jdbcUrl());
        assertEquals("alice", target.username());
        assertEquals("pw", target.password());
    }

    @Test
    void postgresUrlWithoutCredentialsIsAllowed() {
        JdbcTarget target = JdbcTarget.parse("postgresql://db.host:5432/dg");
        assertEquals("jdbc:postgresql://db.host:5432/dg", target.jdbcUrl());
        assertNull(target.username());
        assertNull(target.password());
    }

    @Test
    void jdbcUrlWithQueryCredentialsIsParsedAndStripped() {
        JdbcTarget target = JdbcTarget.parse(
                "jdbc:postgresql://db.host:5432/dg?user=alice&password=pw&sslmode=require");
        assertEquals("jdbc:postgresql://db.host:5432/dg?sslmode=require", target.jdbcUrl());
        assertEquals("alice", target.username());
        assertEquals("pw", target.password());
    }

    @Test
    void credentialsNeverRemainInTheUrl() {
        JdbcTarget target = JdbcTarget.parse("jdbc:postgresql://h/d?password=topsecret&user=u");
        assertTrue(!target.jdbcUrl().contains("topsecret"), "口令不能留在 URL 中被打印");
        assertTrue(!target.jdbcUrl().contains("password="));
        assertEquals("topsecret", target.password());
    }

    @Test
    void unsupportedSchemeIsRejected() {
        ScheduleException error = assertThrows(ScheduleException.class,
                () -> JdbcTarget.parse("mysql://user:pw@host/db"));
        assertTrue(error.getMessage().contains("mysql"));
    }

    @Test
    void unparseableDsnIsRejectedAndRedactedInTheMessage() {
        ScheduleException error = assertThrows(ScheduleException.class,
                () -> JdbcTarget.parse("this is not a dsn"));
        assertTrue(error.getMessage().contains("无法识别"));
    }

    @Test
    void blankDsnIsRejected() {
        assertThrows(ScheduleException.class, () -> JdbcTarget.parse("  "));
    }
}
