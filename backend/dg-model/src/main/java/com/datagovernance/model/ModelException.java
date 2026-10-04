package com.datagovernance.model;

/** 模型定义或变更非法。 */
public class ModelException extends RuntimeException {

    public ModelException(String message) {
        super(message);
    }

    public ModelException(String message, Throwable cause) {
        super(message, cause);
    }

    public static ModelException of(String format, Object... args) {
        return new ModelException(String.format(format, args));
    }
}
