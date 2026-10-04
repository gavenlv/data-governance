package com.datagovernance.core;

import java.util.List;
import java.util.Map;

/**
 * 一次 aspect 写入的结果。
 *
 * @param urn            实体 URN
 * @param aspectType     aspect 类型
 * @param version        写入后的版本号
 * @param eventSeq       事件序号（-1 表示未产生事件）
 * @param changedFields  发生变化的字段
 * @param protectedFields 因来源优先级保护而被拒绝覆盖的字段
 * @param created        是否新建
 */
public record UpsertResult(
        String urn,
        String aspectType,
        long version,
        long eventSeq,
        List<String> changedFields,
        Map<String, Object> protectedFields,
        boolean created) {

    /** 数据与来源均未变化（采集重复提交时可据此避免产生无意义事件）。 */
    public boolean noop() {
        return changedFields.isEmpty() && protectedFields.isEmpty() && !created;
    }
}
