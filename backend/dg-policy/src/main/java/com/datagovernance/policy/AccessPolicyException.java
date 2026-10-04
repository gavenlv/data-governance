package com.datagovernance.policy;

/** 访问治理与策略错误（申请非法、审批越权、策略无法编译等）。 */
public class AccessPolicyException extends RuntimeException {

    public AccessPolicyException(String message) {
        super(message);
    }
}
