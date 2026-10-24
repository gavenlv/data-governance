package com.datagovernance.api.web;

import java.util.LinkedHashMap;
import java.util.Map;

import com.datagovernance.api.embed.EmbedCardService;
import com.datagovernance.api.embed.EmbedTokenService;
import com.datagovernance.api.security.Subjects;
import com.datagovernance.policy.AccessPolicy;
import com.datagovernance.policy.Subject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 嵌入式资产卡（docs/14 §5、docs/09 §9.10）。
 *
 * <p>两个端点分工明确：
 * <ul>
 *   <li>{@code POST /api/v1/embed/token}：**需要平台登录**，用真实主体判定可见性后签发
 *       短期、单资产、只读的令牌；</li>
 *   <li>{@code GET /api/v1/embed/assets/{urn}}：**不需要平台登录**（iframe 无法带 Bearer），
 *       但必须带令牌；只返回字段白名单。</li>
 * </ul>
 *
 * <p>这条分工是这个能力的安全前提：如果没有独立端点而是复用管理接口，
 * "嵌入"就会变成绕过权限的入口 —— 把令牌换成一个能读任意资产的会话即可。
 */
@RestController
@RequestMapping("/api/v1/embed")
public class EmbedController {

    private final EmbedTokenService tokens;
    private final EmbedCardService cards;

    public EmbedController(EmbedTokenService tokens, EmbedCardService cards) {
        this.tokens = tokens;
        this.cards = cards;
    }

    /** 签发嵌入令牌（需要 {@code asset:read} 且该资产对当前主体可见）。 */
    @PostMapping("/token")
    public Map<String, Object> token(@RequestBody Map<String, Object> body) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        Object urn = body == null ? null : body.get("urn");
        if (urn == null || String.valueOf(urn).isBlank()) {
            throw new IllegalArgumentException("缺少 urn");
        }
        String target = String.valueOf(urn);
        cards.ensureVisible(subject, target);

        Long ttl = null;
        Object requested = body.get("ttlSeconds");
        if (requested instanceof Number number) {
            ttl = number.longValue();
        }
        EmbedTokenService.MintedToken minted = tokens.mint(target, subject.id(), ttl);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("token", minted.token());
        payload.put("expiresAt", minted.expiresAt().toString());
        payload.put("ttlSeconds", minted.ttlSeconds());
        payload.put("embedUrl", "/embed/" + encode(target) + "?t=" + minted.token());
        return payload;
    }

    /** 只读投影（令牌校验失败 → 401；资产不存在 → 404）。 */
    @GetMapping("/assets/{urn}")
    public ResponseEntity<Map<String, Object>> card(@PathVariable String urn,
                                                    @RequestParam(name = "t", required = false) String token) {
        try {
            tokens.verify(token, urn);
        } catch (EmbedTokenService.InvalidToken e) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("error", "invalid_embed_token");
            payload.put("message", e.getMessage());
            payload.put("hint", "嵌入令牌短期有效且绑定单个资产：请重新签发"
                    + "（POST /api/v1/embed/token），不要把它写进长期配置");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(payload);
        }
        return ResponseEntity.ok(cards.card(urn));
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
