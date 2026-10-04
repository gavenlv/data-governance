package com.datagovernance.api.web;

import java.io.IOException;
import java.sql.SQLException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JDBC 类型 → JSON 的序列化适配。
 *
 * <p><b>为什么必须在全局做这件事</b>：PostgreSQL 的 {@code text[]} 列经 JdbcTemplate 返回的是
 * {@link java.sql.Array}（PgArray）。Jackson 默认不认识它，会顺着
 * {@code getResultSet().getStatement().getConnection()...} 一路爬到 JDBC 内部对象并抛
 * "No serializer found for class org.postgresql.jdbc.PgArray" —— 结果是接口 500，
 * 而且**把数据库连接对象交给了序列化器**。
 *
 * <p>这个坑在本项目里出现过两次（Batch 1 的检索结果、Batch 4 的授权列表）。
 * 第二次出现说明"逐处手工转换"不是解决办法：只要有一条新 SQL 查了数组列就会再犯。
 * 因此在这里一次性解决 —— 任何接口返回的数组列都会变成普通 JSON 数组。
 */
@Configuration
public class JdbcJsonConfig {

    @Bean
    public Module jdbcArrayModule() {
        SimpleModule module = new SimpleModule("jdbc-array");
        module.addSerializer(java.sql.Array.class, new JsonSerializer<java.sql.Array>() {
            @Override
            public void serialize(java.sql.Array value, JsonGenerator generator,
                                  SerializerProvider serializers) throws IOException {
                try {
                    Object array = value.getArray();
                    if (array instanceof Object[] objects) {
                        generator.writeStartArray();
                        for (Object item : objects) {
                            generator.writeObject(item);
                        }
                        generator.writeEndArray();
                    } else {
                        generator.writeNull();
                    }
                } catch (SQLException e) {
                    // 读不出来就写 null，但绝不把 JDBC 内部对象暴露给序列化器
                    generator.writeNull();
                }
            }
        });
        return module;
    }
}
