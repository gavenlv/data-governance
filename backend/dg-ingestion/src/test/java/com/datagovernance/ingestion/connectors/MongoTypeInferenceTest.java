package com.datagovernance.ingestion.connectors;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MongoDB 类型推断测试。
 *
 * <p>这些规则决定了用户在目录里看到的"表结构"，而推断错误**不会报错** ——
 * 只会让人看到一个安静地不正确的结构。因此把每条规则都写成断言。
 */
class MongoTypeInferenceTest {

    @Test
    void scalarTypesAreNormalized() {
        assertEquals("string", MongoTypeInference.scalarType("abc"));
        assertEquals("boolean", MongoTypeInference.scalarType(true));
        assertEquals("date", MongoTypeInference.scalarType(new java.util.Date()));
        assertEquals("objectId", MongoTypeInference.scalarType(new ObjectId()));
        assertEquals("null", MongoTypeInference.scalarType(null));
    }

    /** 数值族必须收敛：int32/int64/double/decimal 混用是 MongoDB 常态，判成 mixed 只会产生噪音。 */
    @Test
    void numericFamilyCollapsesToNumber() {
        assertEquals("number", MongoTypeInference.scalarType(1));
        assertEquals("number", MongoTypeInference.scalarType(1L));
        assertEquals("number", MongoTypeInference.scalarType(1.5d));
        assertEquals("number", MongoTypeInference.scalarType(new org.bson.types.Decimal128(new java.math.BigDecimal("1.5"))));

        Set<String> types = new LinkedHashSet<>(List.of("number"));
        assertEquals("number", MongoTypeInference.summarize(types, false),
                "只有 number 时不应出现 mixed");
    }

    /** 跨族不一致才是真正的治理信号，必须显式暴露。 */
    @Test
    void crossFamilyInconsistencyIsExposedAsMixed() {
        Set<String> types = new LinkedHashSet<>(List.of("string", "number"));
        assertEquals("mixed(string|number)", MongoTypeInference.summarize(types, false));

        Set<String> threeFamilies = new LinkedHashSet<>(List.of("string", "number", "object"));
        assertEquals("mixed(string|number|object)", MongoTypeInference.summarize(threeFamilies, false));
    }

    @Test
    void arrayTypesDescribeElementType() {
        assertEquals("array<string>", MongoTypeInference.arrayType(List.of("a", "b")));
        assertEquals("array<number>", MongoTypeInference.arrayType(List.of(1, 2, 3)));
        assertEquals("array<object>", MongoTypeInference.arrayType(List.of(new Document("k", "v"))));
    }

    /** 空数组不携带元素类型信息，必须记为 unknown 而不是猜一个类型。 */
    @Test
    void emptyArrayIsUnknownNotEmptyTyped() {
        assertEquals("array<unknown>", MongoTypeInference.arrayType(List.of()));
        assertEquals("array<unknown>", MongoTypeInference.arrayType(null));
    }

    /** 元素类型不一致 → array<mixed>。 */
    @Test
    void mixedElementTypesAreReported() {
        assertEquals("array<mixed>", MongoTypeInference.arrayType(List.of("a", 1)));
    }

    /** 空数组与具体数组类型并存时，应以具体类型为准（空数组是通配）。 */
    @Test
    void untypedArrayIsWildcardWhenConcreteTypePresent() {
        Set<String> types = new LinkedHashSet<>(List.of("array<unknown>", "array<string>"));
        assertEquals("array<string>", MongoTypeInference.summarize(types, false));

        Set<String> onlyUntyped = new LinkedHashSet<>(List.of("array<unknown>"));
        assertEquals("array<unknown>", MongoTypeInference.summarize(onlyUntyped, false));
    }

    /** 可空的两种来源：字段缺失、或见过 null 值。 */
    @Test
    void nullabilityCoversBothMissingFieldAndNullValue() {
        assertTrue(MongoTypeInference.nullable(3, 10, false), "只在部分文档出现的字段是可空的");
        assertTrue(MongoTypeInference.nullable(10, 10, true), "每条文档都有该字段但出现过 null，也是可空的");
        assertFalse(MongoTypeInference.nullable(10, 10, false));
    }

    @Test
    void coverageNoteReportsSparseFieldsAndNulls() {
        assertEquals("采样中每条文档都有该字段",
                MongoTypeInference.coverageNote(10, 10, false));
        assertTrue(MongoTypeInference.coverageNote(3, 10, false).contains("30%"));
        assertTrue(MongoTypeInference.coverageNote(10, 10, true).contains("null"));
        assertEquals("采样为空", MongoTypeInference.coverageNote(0, 0, false));
    }

    @Test
    void onlyNullValuesIsReportedAsNullType() {
        assertEquals("null", MongoTypeInference.summarize(Set.of(), true));
        assertEquals("unknown", MongoTypeInference.summarize(Set.of(), false));
    }
}
