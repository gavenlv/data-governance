package com.datagovernance.sdk.client;

/**
 * 平台 API 返回的业务错误（4xx/5xx）。
 *
 * <p>刻意不复用 {@code IOException}：调用方需要区分"我发错了"（400/422）、
 * "我没权限"（401/403）、"服务端出错"（5xx），而这三类的处理方式完全不同 ——
 * 混成一个异常会让重试逻辑把 400 也重试一遍。
 *
 * <p>{@code hint} 来自平台统一错误响应（{@code ApiExceptionHandler}）：平台在错误里
 * 写"下一步做什么"（例如侧车没启动该跑什么命令），SDK 原样带出去比吞掉有用。
 */
public class DgApiException extends RuntimeException {

    private final int status;
    private final String error;
    private final String hint;

    public DgApiException(int status, String error, String message, String hint) {
        super(message == null || message.isBlank() ? "HTTP " + status : message);
        this.status = status;
        this.error = error;
        this.hint = hint;
    }

    public int status() {
        return status;
    }

    public String error() {
        return error;
    }

    public String hint() {
        return hint;
    }

    public boolean isUnauthorized() {
        return status == 401;
    }

    public boolean isForbidden() {
        return status == 403;
    }

    public boolean isNotFound() {
        return status == 404;
    }

    /** 5xx 或 429：这类才值得退避重试。 */
    public boolean isRetryable() {
        return status >= 500 || status == 429;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("HTTP ").append(status);
        if (error != null && !error.isBlank()) {
            builder.append(" (").append(error).append(')');
        }
        builder.append(": ").append(getMessage());
        if (hint != null && !hint.isBlank()) {
            builder.append(" —— ").append(hint);
        }
        return builder.toString();
    }
}
