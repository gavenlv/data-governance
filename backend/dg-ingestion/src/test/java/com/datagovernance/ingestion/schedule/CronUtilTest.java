package com.datagovernance.ingestion.schedule;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * cron 归一化与下次执行时间。
 *
 * <p>这里的测试针对一个真实存在的坑：配置里写的是 5 段标准 cron，而 Spring 只认 6 段。
 * 不做归一化就会在"应用调度"时报错，或者更糟 —— 被静默解释成别的语义。
 */
class CronUtilTest {

    @Test
    void fiveFieldCronGetsSecondsPrepended() {
        assertEquals("0 0 3 * * *", CronUtil.normalize("0 3 * * *"));
        assertEquals("0 */30 * * * *", CronUtil.normalize("*/30 * * * *"));
    }

    @Test
    void sixFieldCronIsLeftAlone() {
        assertEquals("30 0 3 * * *", CronUtil.normalize("30 0 3 * * *"));
    }

    @Test
    void invalidFieldCountIsRejectedWithReadableMessage() {
        ScheduleException error = assertThrows(ScheduleException.class,
                () -> CronUtil.normalize("0 3 *"));
        assertTrue(error.getMessage().contains("5 段"));
    }

    @Test
    void blankCronIsRejected() {
        assertThrows(ScheduleException.class, () -> CronUtil.normalize("  "));
    }

    @Test
    void invalidExpressionIsRejected() {
        assertThrows(ScheduleException.class, () -> CronUtil.validate("99 99 99 99 99"));
    }

    @Test
    void nextRunUsesDeclaredTimezone() {
        // 每天 03:00（Asia/Shanghai = UTC+8）→ UTC 时间应为前一天 19:00
        Instant from = ZonedDateTime.of(2026, 5, 1, 10, 0, 0, 0, ZoneOffset.UTC).toInstant();
        Instant next = CronUtil.next("0 3 * * *", "Asia/Shanghai", from);
        assertNotNull(next);
        assertEquals(ZonedDateTime.of(2026, 5, 1, 19, 0, 0, 0, ZoneOffset.UTC).toInstant(), next);
    }

    @Test
    void stepCronAdvancesWithinTheHour() {
        Instant from = ZonedDateTime.of(2026, 5, 1, 10, 1, 30, 0, ZoneOffset.UTC).toInstant();
        Instant next = CronUtil.next("*/5 * * * *", "UTC", from);
        assertEquals(ZonedDateTime.of(2026, 5, 1, 10, 5, 0, 0, ZoneOffset.UTC).toInstant(), next);
    }

    @Test
    void invalidTimezoneIsRejected() {
        assertThrows(ScheduleException.class,
                () -> CronUtil.next("0 3 * * *", "Mars/Olympus", Instant.now()));
    }
}
