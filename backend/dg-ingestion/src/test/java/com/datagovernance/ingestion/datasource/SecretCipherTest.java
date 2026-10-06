package com.datagovernance.ingestion.datasource;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 凭据加密的行为测试。
 *
 * <p>重点不在"能加解密"（那是显然的），而在**失败路径**：
 * 未配置密钥必须拒绝、密文被篡改必须报错、换错密钥必须报错。
 * 一个"解不开时返回空字符串"的实现会让扫描静默连到错误的库上。
 */
class SecretCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[]{
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
            17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});

    @Test
    void 加解密往返且密文不含明文() {
        SecretCipher cipher = new SecretCipher(KEY);
        String plain = "postgresql://postgres:root@localhost:25011/dg";

        String encrypted = cipher.encrypt(plain);

        assertThat(encrypted).startsWith("v1:");
        assertThat(encrypted).doesNotContain("postgres").doesNotContain("root");
        assertThat(cipher.decrypt(encrypted)).isEqualTo(plain);
    }

    @Test
    void 同一明文两次加密的密文不同() {
        SecretCipher cipher = new SecretCipher(KEY);

        String first = cipher.encrypt("same");
        String second = cipher.encrypt("same");

        // 每次使用新随机 IV：密文相同会让"两条记录是否同一个口令"变成可观测事实
        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("same");
        assertThat(cipher.decrypt(second)).isEqualTo("same");
    }

    @Test
    void 未配置密钥时拒绝加密而不是静默放行() {
        SecretCipher cipher = new SecretCipher("");

        assertThat(cipher.configured()).isFalse();
        assertThat(cipher.notConfiguredReason()).contains("DG_SECRET_KEY");
        assertThatThrownBy(() -> cipher.encrypt("secret"))
                .isInstanceOf(SecretCipher.NotConfigured.class)
                .hasMessageContaining("明文落库");
    }

    @Test
    void 密钥长度不对时视为未配置并给出原因() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});

        SecretCipher cipher = new SecretCipher(tooShort);

        assertThat(cipher.configured()).isFalse();
        assertThat(cipher.notConfiguredReason()).contains("3 字节", "32 字节");
    }

    @Test
    void 非Base64密钥视为未配置并给出原因() {
        SecretCipher cipher = new SecretCipher("这不是 base64 !!");

        assertThat(cipher.configured()).isFalse();
        assertThat(cipher.notConfiguredReason()).contains("Base64");
    }

    @Test
    void 密文被篡改时解密失败而不是返回垃圾() {
        SecretCipher cipher = new SecretCipher(KEY);
        String encrypted = cipher.encrypt("postgresql://host:5432/db");
        // 改掉密文最后一个字符（认证标签会被破坏）
        String tampered = encrypted.substring(0, encrypted.length() - 1)
                + (encrypted.endsWith("A") ? "B" : "A");

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("解密失败");
    }

    @Test
    void 换过密钥的旧密文解不开且报错可读() {
        String otherKey = Base64.getEncoder().encodeToString(new byte[32]);
        String encrypted = new SecretCipher(KEY).encrypt("postgresql://host:5432/db");

        assertThatThrownBy(() -> new SecretCipher(otherKey).decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DG_SECRET_KEY");
    }

    @Test
    void 无法识别的密文格式被明确拒绝() {
        SecretCipher cipher = new SecretCipher(KEY);

        assertThatThrownBy(() -> cipher.decrypt("plain-text-not-encrypted"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("密文格式");
    }
}