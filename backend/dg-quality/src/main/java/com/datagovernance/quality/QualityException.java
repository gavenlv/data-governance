package com.datagovernance.quality;

/** 质量子系统错误（规则不合法、无法编译、无法执行）。 */
public class QualityException extends RuntimeException {

    public QualityException(String message) {
        super(message);
    }

    public QualityException(String message, Throwable cause) {
        super(message, cause);
    }
}
