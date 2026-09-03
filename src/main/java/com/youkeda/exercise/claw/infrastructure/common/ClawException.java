package com.youkeda.exercise.claw.infrastructure.common;

/**
 * Claw Assistant 自定义异常
 * 用于统一处理所有业务异常
 */
public class ClawException extends Exception {

    public ClawException(String message) {
        super(message);
    }

    public ClawException(String message, Throwable cause) {
        super(message, cause);
    }
}
