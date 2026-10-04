package com.datagovernance.ingestion.schedule;

/** 调度定义非法（校验失败时抛出，消息面向使用者而不是开发者）。 */
public class ScheduleException extends RuntimeException {

    public ScheduleException(String message) {
        super(message);
    }
}
