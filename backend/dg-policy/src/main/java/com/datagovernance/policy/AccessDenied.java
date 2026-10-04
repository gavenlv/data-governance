package com.datagovernance.policy;

/** 授权判定失败（权限点不足或资产分级超出可见范围）。 */
public class AccessDenied extends RuntimeException {

    private final String reason;

    public AccessDenied(String message) {
        super(message);
        this.reason = message;
    }

    public String reason() {
        return reason;
    }
}
