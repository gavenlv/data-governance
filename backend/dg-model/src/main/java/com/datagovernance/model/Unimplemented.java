package com.datagovernance.model;

import java.util.Optional;

/**
 * 标记「已设计但尚未实现」的能力。
 *
 * <p>项目纪律（docs/21 §3）：<b>未实现的功能必须显式标注，不允许静默返回空或假数据</b>。
 * 这是对开源平台通病的直接纠正 —— 用户无法区分「真的没有数据」与「功能没做」。
 *
 * <p>标注方式有三处，缺一不可：
 * <ol>
 *   <li>代码：本注解（含设计文档章节 + 计划交付阶段）</li>
 *   <li>接口：运行时通过 {@code /api/v1/capabilities} 暴露实现状态</li>
 *   <li>界面：状态徽标 + 设计说明（docs/14）</li>
 * </ol>
 */
public @interface Unimplemented {

    /** 设计文档中的出处，例如 "docs/09 §9.4"。 */
    String doc();

    /** 计划交付阶段，例如 "Phase 1"。 */
    String phase();

    /** 一句话说明该能力将提供什么。 */
    String summary();
}
