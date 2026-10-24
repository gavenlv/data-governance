package com.datagovernance.ai.embedding;

import java.time.Duration;
import java.util.Locale;

import com.datagovernance.ai.embedding.EmbeddingProvider.EmbeddingUnavailable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 按配置装配 embedding 提供方。
 *
 * <p>未识别或缺失的 provider **启动即失败**（而不是运行期才发现）：
 * 一个写错的配置名如果被当成 {@code none} 处理，使用者会以为"已经开了语义检索"。
 */
@Configuration
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingConfiguration {

    @Bean
    public EmbeddingProvider embeddingProvider(EmbeddingProperties properties) {
        String provider = properties.getProvider() == null ? "none"
                : properties.getProvider().trim().toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "hash" -> new HashingEmbeddingProvider(properties.getDimension());
            case "openai" -> new HttpEmbeddingProvider(
                    "openai:" + properties.getModel() + ":" + properties.getDimension(),
                    properties.getBaseUrl(), properties.getModel(), properties.getApiKey(),
                    properties.getDimension(), Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())));
            case "none" -> new Unavailable();
            default -> throw new IllegalStateException(
                    "dg.ai.embedding.provider 无效：" + properties.getProvider()
                            + "（可选 none | hash | openai）");
        };
    }

    /** 未配置模型时的占位实现：可用性为 false，调用即给出"怎么配"的错误。 */
    static final class Unavailable implements EmbeddingProvider {

        @Override
        public String id() {
            return "none";
        }

        @Override
        public int dimension() {
            return 0;
        }

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public float[] embed(String text) {
            throw new EmbeddingUnavailable(
                    "未配置 embedding 服务：混合检索的词法与术语扩展仍可用，但**没有向量召回**。"
                            + "要启用语义检索请设置 DG_AI_EMBEDDING_PROVIDER=openai 与 "
                            + "DG_AI_EMBEDDING_BASE_URL / DG_AI_EMBEDDING_MODEL");
        }
    }
}
