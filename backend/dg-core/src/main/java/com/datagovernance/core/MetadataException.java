package com.datagovernance.core;

/** 元数据操作异常族。 */
public class MetadataException extends RuntimeException {

    public MetadataException(String message) {
        super(message);
    }

    /** 对象不存在。 */
    public static class NotFound extends MetadataException {
        public NotFound(String message) {
            super(message);
        }
    }

    /** 乐观锁冲突或状态冲突。 */
    public static class Conflict extends MetadataException {
        public Conflict(String message) {
            super(message);
        }
    }

    /** 模型校验失败。 */
    public static class ValidationFailed extends MetadataException {

        private final java.util.List<String> errors;

        public ValidationFailed(java.util.List<String> errors) {
            super(String.join("; ", errors));
            this.errors = java.util.List.copyOf(errors);
        }

        public java.util.List<String> errors() {
            return errors;
        }
    }
}
