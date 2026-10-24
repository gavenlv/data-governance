package com.datagovernance.api;

import java.util.Arrays;

import com.datagovernance.api.cli.Dgctl;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 数据治理平台 · 控制面入口（Java 21 + Spring Boot 3）。
 *
 * <p>模块装配：本应用把 dg-core（元数据内核）/ dg-ingestion（采集）/ dg-lineage（血缘）
 * 与 dg-quality / dg-policy / dg-ai / dg-sdk（Java 客户端）装配在一起，
 * 并通过 {@code /api/v1/capabilities} 对外声明每项能力的实现状态。
 *
 * <p>{@code @EnableScheduling} 支撑两个常驻后台任务：
 * 检索索引消费者（{@code IndexMaintenanceJob}）与采集调度器（{@code ScheduleRunner}）。
 *
 * <p>**CLI 模式**：首参为 {@code dgctl}（或 {@code cli}）时不启动 Spring 容器，
 * 直接执行命令行并以其退出码退出。理由见 {@link Dgctl} 的类注释 ——
 * 一条诊断命令不该因为数据库连不上（或端口被占）而失败。
 */
@SpringBootApplication(scanBasePackages = "com.datagovernance")
@EnableScheduling
public class DataGovernanceApplication {

    public static void main(String[] args) {
        if (args.length > 0 && ("dgctl".equals(args[0]) || "cli".equals(args[0]))) {
            System.exit(Dgctl.run(Arrays.copyOfRange(args, 1, args.length)));
        }
        SpringApplication.run(DataGovernanceApplication.class, args);
    }
}
