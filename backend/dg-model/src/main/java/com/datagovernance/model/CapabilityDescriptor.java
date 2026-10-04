package com.datagovernance.model;

import java.util.List;

/**
 * 一项能力及其实现状态（docs/21 §3 的「未实现必须显式标注」）。
 *
 * <p>用途：{@code /api/v1/capabilities} 暴露给界面，界面为每个功能渲染状态徽标与设计说明。
 * 这样「哪些能用、哪些是占位」对使用者是透明的，而不是靠试错发现。
 *
 * @param id       能力标识（与 UI 路由/菜单项对应），如 {@code quality.rules}
 * @param name     中文名
 * @param domain   所属域（对应 docs/07 §2 的 D1–D8）
 * @param status   实现状态
 * @param doc      设计文档出处，如 {@code docs/09 §9.4}
 * @param phase    计划交付阶段，如 {@code Phase 1}
 * @param summary  该能力将提供什么（一句话）
 * @param notes    缺口清单（PARTIAL / NOT_IMPLEMENTED 时必填）
 */
public record CapabilityDescriptor(
        String id,
        String name,
        String domain,
        CapabilityStatus status,
        String doc,
        String phase,
        String summary,
        List<String> notes) {

    public CapabilityDescriptor {
        notes = List.copyOf(notes == null ? List.of() : notes);
    }

    public static CapabilityDescriptor implemented(String id, String name, String domain,
                                                   String doc, String summary) {
        return new CapabilityDescriptor(id, name, domain, CapabilityStatus.IMPLEMENTED,
                doc, "-", summary, List.of());
    }

    public static CapabilityDescriptor partial(String id, String name, String domain,
                                               String doc, String phase, String summary,
                                               List<String> notes) {
        return new CapabilityDescriptor(id, name, domain, CapabilityStatus.PARTIAL,
                doc, phase, summary, notes);
    }

    public static CapabilityDescriptor notImplemented(String id, String name, String domain,
                                                      String doc, String phase, String summary,
                                                      List<String> notes) {
        return new CapabilityDescriptor(id, name, domain, CapabilityStatus.NOT_IMPLEMENTED,
                doc, phase, summary, notes);
    }
}
