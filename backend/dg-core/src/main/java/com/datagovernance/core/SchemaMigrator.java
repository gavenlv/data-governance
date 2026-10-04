package com.datagovernance.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * schema 迁移：按文件名顺序执行 {@code sql/*.sql}（幂等，全部语句使用 IF NOT EXISTS）。
 *
 * <p>为什么不用 Flyway：本项目的 schema 由同一套 SQL 同时服务 Python 参考实现与 Java
 * 控制面，直接执行脚本可保证两者看到同一份 DDL。生产化时可平滑替换为 Flyway
 * （docs/10 §3.1 已预留该位置），脚本本身无需改动。
 *
 * <p>可用 {@code dg.migrate-on-start=false} 关闭（由 DBA 手工执行时使用）。
 */
@Component
public class SchemaMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);

    private final JdbcTemplate jdbc;
    private final String sqlDir;
    private final boolean enabled;

    public SchemaMigrator(
            JdbcTemplate jdbc,
            @Value("${dg.sql-dir:../sql}") String sqlDir,
            @Value("${dg.migrate-on-start:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.sqlDir = sqlDir;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("已跳过 schema 迁移（dg.migrate-on-start=false）");
            return;
        }
        Path dir = Path.of(sqlDir);
        if (!Files.isDirectory(dir)) {
            log.warn("迁移目录不存在，跳过：{}", dir.toAbsolutePath());
            return;
        }

        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        int statements = 0;
        for (Path file : files) {
            String script;
            try {
                script = Files.readString(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            for (String statement : split(script)) {
                jdbc.execute(statement);
                statements++;
            }
            log.info("已应用迁移 {}", file.getFileName());
        }
        log.info("schema 就绪：{} 个迁移文件、{} 条语句", files.size(), statements);
    }

    /** 按分号切分（本项目 SQL 不含函数体，简单切分足够）。 */
    static List<String> split(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (String line : script.split("\r?\n")) {
            if (line.stripLeading().startsWith("--")) {
                continue;
            }
            buffer.append(line).append('\n');
            if (line.indexOf(';') >= 0) {
                for (String part : buffer.toString().split(";")) {
                    if (!part.isBlank()) {
                        out.add(part.strip());
                    }
                }
                buffer.setLength(0);
            }
        }
        if (!buffer.toString().isBlank()) {
            out.add(buffer.toString().strip());
        }
        return out;
    }
}
