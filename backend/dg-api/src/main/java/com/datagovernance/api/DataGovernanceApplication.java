package com.datagovernance.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 数据治理平台 · 控制面入口（Java 21 + Spring Boot 3）。
 *
 * <p>模块装配：本应用把 dg-core（元数据内核）/ dg-ingestion（采集）/ dg-lineage（血缘）
 * 与 dg-quality / dg-policy / dg-ai / dg-sdk（骨架）装配在一起，
 * 并通过 {@code /api/v1/capabilities} 对外声明每项能力的实现状态。
 *
 * <p>{@code @EnableScheduling} 支撑两个常驻后台任务：
 * 检索索引消费者（{@code IndexMaintenanceJob}）与采集调度器（{@code ScheduleRunner}）。
 */
@SpringBootApplication(scanBasePackages = "com.datagovernance")
@EnableScheduling
public class DataGovernanceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataGovernanceApplication.class, args);
    }
}
