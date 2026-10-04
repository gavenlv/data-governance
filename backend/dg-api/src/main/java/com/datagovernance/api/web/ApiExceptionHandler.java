package com.datagovernance.api.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.core.MetadataException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 统一异常处理：把内部异常映射为稳定的 HTTP 语义。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MetadataException.NotFound.class)
    public ResponseEntity<Map<String, Object>> notFound(MetadataException.NotFound e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(MetadataException.Conflict.class)
    public ResponseEntity<Map<String, Object>> conflict(MetadataException.Conflict e) {
        return body(HttpStatus.CONFLICT, e.getMessage());
    }

    /** 授权失败 → 403（判定来自 policy.AccessPolicy 这一唯一入口）。 */
    @ExceptionHandler(com.datagovernance.policy.AccessDenied.class)
    public ResponseEntity<Map<String, Object>> forbidden(com.datagovernance.policy.AccessDenied e) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", "forbidden");
        payload.put("message", e.reason());
        payload.put("hint", "授权判定唯一入口：com.datagovernance.policy.AccessPolicy（docs/09 §9.7）");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(payload);
    }

    /** SQL 解析侧车不可用 → 502：必须显式失败，不能静默返回空血缘（docs/09 §9.2）。 */
    @ExceptionHandler(com.datagovernance.lineage.SqlParseSidecarClient.SidecarUnavailable.class)
    public ResponseEntity<Map<String, Object>> sidecarUnavailable(
            com.datagovernance.lineage.SqlParseSidecarClient.SidecarUnavailable e) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", "sidecar_unavailable");
        payload.put("message", e.getMessage());
        payload.put("hint", "启动侧车：python -m dg.cli sidecar（或设 DG_LINEAGE_SIDECAR_URL）；"
                + "侧车不可用时不会返回空血缘，因为空血缘与'解析没跑'必须可区分");
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(payload);
    }

    /** SQL 解析侧车明确拒绝请求（4xx，例如方言不支持）→ 400：这是调用方的问题。 */
    @ExceptionHandler(com.datagovernance.lineage.SqlParseSidecarClient.ParseRejected.class)
    public ResponseEntity<Map<String, Object>> parseRejected(
            com.datagovernance.lineage.SqlParseSidecarClient.ParseRejected e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 质量/契约子系统错误 → 422（请求语义正确但内容不合法，例如规则无法编译）。 */
    @ExceptionHandler(com.datagovernance.quality.QualityException.class)
    public ResponseEntity<Map<String, Object>> quality(com.datagovernance.quality.QualityException e) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    /** 访问治理错误 → 422（申请不合法、无权裁决、策略无法编译、复核证据不足等）。 */
    @ExceptionHandler(com.datagovernance.policy.AccessPolicyException.class)
    public ResponseEntity<Map<String, Object>> accessPolicy(
            com.datagovernance.policy.AccessPolicyException e) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(MetadataException.ValidationFailed.class)
    public ResponseEntity<Map<String, Object>> validation(MetadataException.ValidationFailed e) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", "validation_failed");
        payload.put("errors", e.errors());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(payload);
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<Map<String, Object>> notImplemented(UnsupportedOperationException e) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", "not_implemented");
        payload.put("message", e.getMessage());
        payload.put("hint", "见 /api/v1/capabilities 中对应能力的 state 与 notes（docs/21 §3 的标注纪律）");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(payload);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", status.name().toLowerCase());
        payload.put("message", message);
        return ResponseEntity.status(status).body(payload);
    }

    /** 允许控制器返回空列表时的统一形状。 */
    public static Map<String, Object> list(String key, List<?> items) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", items.size());
        payload.put(key, items);
        return payload;
    }
}
