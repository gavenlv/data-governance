package com.datagovernance.model;

/**
 * 能力实现状态。
 *
 * <p>三态是刻意的：只区分「有/无」会掩盖最常见的情况 ——
 * 框架已就位但只实现了主路径（PARTIAL）。
 */
public enum CapabilityStatus {

    /** 已实现并有测试/端到端验证。 */
    IMPLEMENTED,

    /** 部分实现：主路径可用，但存在明确缺口（缺口逐条列在 notes 里）。 */
    PARTIAL,

    /** 未实现：仅有设计与接口占位。 */
    NOT_IMPLEMENTED
}
