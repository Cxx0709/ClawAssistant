package com.youkeda.exercise.claw.feature.transport.didi;

/**
 * 滴滴 MCP 客户端异常
 *
 * <p>封装 MCP JSON-RPC 2.0 通信过程中的各类异常：
 * <ul>
 *   <li>配置缺失（API Key 未设置）</li>
 *   <li>HTTP 通信错误</li>
 *   <li>JSON-RPC 协议错误</li>
 *   <li>响应数据格式错误</li>
 * </ul>
 */
public class DidiMcpException extends RuntimeException {

    public DidiMcpException(String message) {
        super(message);
    }

    public DidiMcpException(String message, Throwable cause) {
        super(message, cause);
    }
}
