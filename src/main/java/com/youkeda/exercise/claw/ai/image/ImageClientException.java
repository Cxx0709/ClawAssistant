package com.youkeda.exercise.claw.ai.image;

/**
 * 图片生成 API 异常
 *
 * 携带 API 返回的错误码，用于上层区分错误类型做针对性处理
 */
public class ImageClientException extends RuntimeException {

    /**
     * API 返回的错误码，如 DataInspectionFailed
     */
    private final String errorCode;

    public ImageClientException(String errorCode, String message) {
        super(errorCode + ": " + message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
