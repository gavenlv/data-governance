package com.datagovernance.ingestion.schedule;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.springframework.scheduling.support.CronExpression;

/**
 * cron 解析（docs/09 §9.1）。
 *
 * <p><b>为什么需要这层薄封装</b>：标准 cron（Vixie / croniter）是 <b>5 段</b>
 * （分 时 日 月 周），而 Spring 的 {@link CronExpression} 是 <b>6 段</b>（秒 分 时 日 月 周）。
 * 直接把 5 段表达式丢给 Spring 会抛异常；直接换掉配置又会让 Git 里的
 * {@code config/schedules.example.yaml}（5 段）失效。
 * 因此显式做归一化，并把这个差异记录下来 —— 而不是让使用者猜。
 *
 * <p>周字段语义一致：0–6（0=周日）或 SUN–SAT；Spring 额外接受 7=周日。
 */
public final class CronUtil {

    private CronUtil() {
    }

    /** 把 5 段标准 cron 归一化为 6 段（补秒 = 0）；已是 6 段则原样返回。 */
    public static String normalize(String cron) {
        if (cron == null || cron.isBlank()) {
            throw new ScheduleException("cron 表达式不能为空");
        }
        String value = cron.trim();
        int fields = value.split("\\s+").length;
        if (fields == 5) {
            return "0 " + value;
        }
        if (fields == 6) {
            return value;
        }
        throw new ScheduleException("cron 表达式应为 5 段（分 时 日 月 周）或 6 段（秒 分 时 日 月 周）：" + cron);
    }

    /** 校验表达式是否合法（不合法时抛 {@link ScheduleException}，消息可读）。 */
    public static void validate(String cron) {
        try {
            CronExpression.parse(normalize(cron));
        } catch (ScheduleException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new ScheduleException("cron 表达式非法：" + cron + "（" + e.getMessage() + "）");
        }
    }

    /** 计算下一次执行时间（按调度声明的时区）。 */
    public static Instant next(String cron, String timezone, Instant from) {
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone.trim());
        } catch (RuntimeException e) {
            throw new ScheduleException("时区非法：" + timezone);
        }
        CronExpression expression = CronExpression.parse(normalize(cron));
        ZonedDateTime base = ZonedDateTime.ofInstant(from == null ? Instant.now() : from, zone);
        ZonedDateTime next = expression.next(base);
        return next == null ? null : next.toInstant();
    }
}
