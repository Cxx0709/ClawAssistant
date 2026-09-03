package com.youkeda.exercise.claw.feature.map;

/**
 * 腾讯地图 API 异常
 *
 * <p>携带 API 返回的错误码，用于上层区分错误类型做针对性处理。
 * 遵循项目中 ImageClientException / VoiceClientException 的异常模式。
 */
public class TencentMapException extends RuntimeException {

    /**
     * API 返回的错误码
     */
    private final String errorCode;

    public TencentMapException(String message) {
        super(message);
        this.errorCode = "MAP_ERROR";
    }

    public TencentMapException(String errorCode, String message) {
        super(errorCode + ": " + message);
        this.errorCode = errorCode;
    }

    public TencentMapException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = "MAP_ERROR";
    }

    public String getErrorCode() {
        return errorCode;
    }
}