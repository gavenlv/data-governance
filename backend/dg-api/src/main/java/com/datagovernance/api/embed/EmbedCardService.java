package com.datagovernance.api.embed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.core.MetadataException;
import com.datagovernance.core.MetadataService;
import com.datagovernance.policy.AccessPolicy;
import com.datagovernance.policy.Subject;
import org.springframework.stereotype.Service;

/**
 * 嵌入式资产卡（docs/14 §5）。
 *
 * <p>核心约束是**字段白名单**：嵌入卡只输出"能放进 BI/IDE 侧边栏"的治理信息，
 * 明确**不包含** schema 明细、连接信息、版本历史、审计记录。理由不是"用不上"，
 * 而是嵌入场景的调用方是**第三方页面**，字段一旦泄漏就不受平台控制。
 *
 * <p>可见性在**签发令牌时**用真实主体判定（{@link AccessPolicy#ensureVisible}），
 * 因此拿到令牌只能读那一个资产；令牌本身也在签名上绑定 URN。
 */
@Service
public class EmbedCardService {

    private final MetadataService metadata;

    public EmbedCardService(MetadataService metadata) {
        this.metadata = metadata;
    }

    /** 签发令牌前的可见性判定：不可见时按"不存在"处理，避免泄漏资产存在性。 */
    public void ensureVisible(Subject subject, String urn) {
        String classification = classificationOf(urn);
        if (!AccessPolicy.canSeeClassification(subject, classification)) {
            throw new MetadataException.NotFound("资产不存在或无权访问：" + urn);
        }
    }

    public String classificationOf(String urn) {
        return metadata.getAspect(urn, "classification")
                .map(data -> data.get("level"))
                .map(String::valueOf)
                .orElse(null);
    }

    /** 白名单投影。新增字段必须在这里显式加，默认不外泄。 */
    public Map<String, Object> card(String urn) {
        MetadataService.EntityRow entity = metadata.getEntity(urn);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("urn", entity.urn());
        payload.put("displayName", entity.displayName());
        payload.put("entityType", entity.entityType());
        payload.put("namespace", entity.namespace());
        payload.put("lifecycle", entity.lifecycle());
        // 未分级资产不省略这个字段：省略会让「没登记分级」看起来像「卡片坏了」，
        // 而 AccessPolicy 对未分级按 L2 保守处理，这里如实标 UNCLASSIFIED。
        String classification = classificationOf(urn);
        payload.put("classification", classification == null ? "UNCLASSIFIED" : classification);
        payload.put("description", descriptionOf(urn));
        payload.put("owners", ownersOf(urn));
        payload.put("updatedAt", entity.updatedAt());

        Map<String, String> links = new LinkedHashMap<>();
        links.put("asset", "/assets?urn=" + encode(urn));
        links.put("requestAccess", "/governance");
        payload.put("links", links);
        payload.put("notice", "该卡片为只读投影：不包含字段结构、连接信息与审计记录；"
                + "完整信息请打开平台资产页（需要登录与相应权限）");
        return payload;
    }

    private String descriptionOf(String urn) {
        return metadata.getAspect(urn, "description")
                .map(data -> {
                    Object text = data.get("text");
                    if (text == null) {
                        text = data.get("description");
                    }
                    return text == null ? null : String.valueOf(text);
                })
                .orElse(null);
    }

    private List<Map<String, Object>> ownersOf(String urn) {
        return metadata.getAspect(urn, "ownership")
                .map(data -> {
                    Object raw = data.get("owners");
                    List<Map<String, Object>> owners = new ArrayList<>();
                    if (raw instanceof List<?> list) {
                        for (Object item : list) {
                            Map<String, Object> owner = new LinkedHashMap<>();
                            if (item instanceof Map<?, ?> map) {
                                owner.put("id", map.get("id"));
                                owner.put("name", map.get("name") == null ? map.get("id") : map.get("name"));
                                owner.put("type", map.get("type"));
                            } else if (item != null) {
                                owner.put("id", String.valueOf(item));
                                owner.put("name", String.valueOf(item));
                            }
                            if (!owner.isEmpty()) {
                                owners.add(owner);
                            }
                        }
                    }
                    return owners;
                })
                .orElse(List.of());
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
