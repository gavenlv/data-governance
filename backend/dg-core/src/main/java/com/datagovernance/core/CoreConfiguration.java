package com.datagovernance.core;

import java.nio.file.Path;

import com.datagovernance.model.ModelRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 内核装配。
 *
 * <p>模型定义是唯一事实源（docs/06 §2.1）：{@code model/**.yaml} 同时被
 * Java 控制面、Python 侧车（血缘/AI）与前端生成物消费。路径通过
 * {@code dg.model-dir} 配置，默认相对仓库根的 {@code model}。
 */
@Configuration
public class CoreConfiguration {

    @Bean
    public ModelRegistry modelRegistry(@Value("${dg.model-dir:../model}") String modelDir) {
        return ModelRegistry.load(Path.of(modelDir));
    }
}
