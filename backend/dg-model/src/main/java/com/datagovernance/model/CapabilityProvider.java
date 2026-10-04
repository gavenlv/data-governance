package com.datagovernance.model;

import java.util.List;

/**
 * 能力声明提供者。
 *
 * <p>每个模块实现本接口，声明自己「做了什么、做到什么程度、还差什么」。
 * {@code dg-api} 聚合全部实现并暴露为 {@code /api/v1/capabilities}，
 * 前端据此渲染状态徽标 —— 这是「未实现必须显式标注」的执行机制。
 */
public interface CapabilityProvider {

    /** 本模块声明的能力清单。 */
    List<CapabilityDescriptor> capabilities();
}
