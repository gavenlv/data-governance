package com.datagovernance.ingestion.datasource;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 数据源凭据的对称加密（AES-256-GCM）。
 *
 * <p>为什么必须有它：数据源管理的前提是"保存连接"，而连接串里通常带着口令
 * （{@code postgresql://user:pass@host/db}）。把口令明文写进元数据库，等于让
 * "一次数据库备份泄漏"直接升级成"所有被治理系统的凭据泄漏"。
 *
 * <p>三条刻意的设计选择：
 * <ol>
 *   <li><b>密钥来自环境变量 {@code DG_SECRET_KEY}，不在库里</b> —— 密文与密钥分离存储，
 *       库被拖走也解不开（这正是加密与"库内自行加解密"的本质区别）。</li>
 *   <li><b>未配置密钥时拒绝保存，而不是退化成明文或"弱默认密钥"</b> ——
 *       静默降级是安全设计里最坏的一种"方便"：使用者以为加密了，其实没有。</li>
 *   <li><b>GCM 而不是 CBC</b> —— GCM 自带完整性校验：密文被篡改会在解密时报错，
 *       而不是解出一段静默损坏的 DSN（错误的连接串比明确的失败危险得多）。</li>
 * </ol>
 *
 * <p>存储格式：{@code v1:<IV base64>:<密文+认证标签 base64>}。
 * 带版本前缀是为了将来换算法时能识别旧密文（可解密旧格式 + 新写入用新格式），
 * 而不是被迫一次性重加密全部数据。
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);

    /** 格式版本前缀：换算法时递增，解密按前缀分派。 */
    private static final String VERSION = "v1";
    /** GCM 推荐 12 字节 IV（96 bit）。 */
    private static final int IV_BYTES = 12;
    /** 认证标签 128 bit（GCM 最大强度）。 */
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;
    private final String notConfiguredReason;

    public SecretCipher(@Value("${dg.secret-key:}") String base64Key) {
        String reason = null;
        SecretKeySpec parsed = null;
        if (base64Key == null || base64Key.isBlank()) {
            reason = "未配置环境变量 DG_SECRET_KEY";
        } else {
            try {
                byte[] raw = Base64.getDecoder().decode(base64Key.trim());
                if (raw.length != KEY_BYTES) {
                    reason = "DG_SECRET_KEY 解码后为 " + raw.length + " 字节，AES-256 需要 "
                            + KEY_BYTES + " 字节";
                } else {
                    parsed = new SecretKeySpec(raw, "AES");
                }
            } catch (IllegalArgumentException e) {
                reason = "DG_SECRET_KEY 不是合法的 Base64：" + e.getMessage();
            }
        }
        this.key = parsed;
        this.notConfiguredReason = reason;
        if (reason == null) {
            log.info("数据源凭据加密已启用（AES-256-GCM，密钥来自 DG_SECRET_KEY）");
        } else {
            log.warn("数据源凭据加密不可用：{} —— 保存数据源连接会被拒绝（不会明文落库）", reason);
        }
    }

    public boolean configured() {
        return key != null;
    }

    /** 密钥不可用的原因（供接口原样返回，让人知道该配什么）。 */
    public String notConfiguredReason() {
        return notConfiguredReason;
    }

    /** 加密：每次调用都用新的随机 IV，因此同一明文两次加密的密文不同。 */
    public String encrypt(String plaintext) {
        requireConfigured();
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(
                    (plaintext == null ? "" : plaintext).getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getEncoder();
            return VERSION + ":" + encoder.encodeToString(iv) + ":" + encoder.encodeToString(ciphertext);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("凭据加密失败：" + e.getMessage(), e);
        }
    }

    /**
     * 解密。密文被篡改、密钥不匹配、格式损坏都会**显式失败**，
     * 而不是返回一段看起来像 DSN 的垃圾。
     */
    public String decrypt(String stored) {
        requireConfigured();
        if (stored == null || stored.isBlank()) {
            throw new IllegalArgumentException("密文为空");
        }
        String[] parts = stored.split(":", 3);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw new IllegalArgumentException(
                    "无法识别的凭据密文格式（期望 " + VERSION + ":<iv>:<ciphertext>）");
        }
        try {
            Base64.Decoder decoder = Base64.getDecoder();
            byte[] iv = decoder.decode(parts[1]);
            byte[] ciphertext = decoder.decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "凭据解密失败：密文损坏、被篡改，或与当前 DG_SECRET_KEY 不匹配（"
                            + "换过密钥的旧数据无法解开，这是预期行为而不是 bug）", e);
        }
    }

    private void requireConfigured() {
        if (key == null) {
            throw new NotConfigured(notConfiguredReason);
        }
    }

    /**
     * 密钥不可用 → 由 {@code ApiExceptionHandler} 映射为 <b>502</b>（服务端配置缺失，
     * 与"AI 未配置大模型"、"侧车未部署"同一类语义）。
     *
     * <p>刻意不是 400：这不是调用方写错了请求，而是部署缺了一项必须显式声明的配置。
     */
    public static class NotConfigured extends RuntimeException {
        public NotConfigured(String reason) {
            super("数据源凭据加密不可用：" + reason
                    + "。为避免明文落库，保存数据源连接已被拒绝。"
                    + "生成密钥：openssl rand -base64 32（得到 Base64 的 32 字节），"
                    + "然后设 DG_SECRET_KEY=<该值> 并重启控制面。");
        }
    }
}