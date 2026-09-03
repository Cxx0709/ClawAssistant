package com.youkeda.exercise.claw.feature.transport.didi.monitor;

import com.youkeda.exercise.claw.feature.transport.didi.DidiMcpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 滴滴订单监听器（预留接口）
 *
 * <p>当前为骨架实现，暂不执行真实定时轮询。
 * 用于未来扩展"订单创建 → 监听司机位置 → 距离判断 → 站内主动提醒"的能力。
 *
 * <p>未来完整调用链：
 * <pre>
 * 用户创建订单
 *     ↓
 * scheduler（定时任务）
 *     ↓
 * DidiMcpClient.callTool("taxi_get_driver_location", {order_id})
 *     ↓
 * 提取司机经纬度
 *     ↓
 * 与起点坐标计算距离
 *     ↓
 * 距离 < 阈值 → 触发站内主动提醒
 * </pre>
 *
 * <p>当前预留接口：{@link #startMonitoring} 和 {@link #stopMonitoring}。
 */
@Component
public class DidiOrderMonitor {

    private static final Logger log = LoggerFactory.getLogger(DidiOrderMonitor.class);

    /** 被监听的订单集合（orderId → 监听上下文） */
    private final ConcurrentHashMap<String, MonitorContext> monitoredOrders = new ConcurrentHashMap<>();

    // ==================== 接口方法 ====================

    /**
     * 开始监听订单。
     * <p>当前为骨架实现，仅记录日志。未来将启动定时轮询。
     *
     * @param orderId  订单 ID
     * @param userId   用户标识
     * @param callback 司机到达时触发的回调（用于写入站内通知）
     */
    public void startMonitoring(String orderId, String userId, Consumer<String> callback) {
        if (orderId == null || orderId.isBlank()) {
            log.warn("订单 ID 为空，跳过监听");
            return;
        }

        MonitorContext context = new MonitorContext(orderId, userId, callback);

        // 如果已在监听中，跳过
        if (monitoredOrders.putIfAbsent(orderId, context) != null) {
            log.debug("订单 {} 已在监听中", orderId);
            return;
        }

        log.info("订单监听已注册（骨架实现）| orderId={} | userId={}", orderId, userId);

        // TODO: 未来实现定时轮询
        // 1. 使用 ScheduledExecutorService / @Scheduled 周期性调用
        //    DidiMcpClient.callTool("taxi_get_driver_location", {order_id: orderId})
        // 2. 提取司机位置 (lat, lng)
        // 3. 与订单起点坐标计算距离
        // 4. 当距离 < 阈值（如 500 米）时触发 callback
        // 5. callback 写入通知："您的滴滴司机即将到达，请准备上车"
        // 6. 监听完成后自动移除

        log.debug("未来实现：定时调用 taxi_get_driver_location | orderId={}", orderId);
    }

    /**
     * 停止监听订单。
     *
     * @param orderId 订单 ID
     */
    public void stopMonitoring(String orderId) {
        if (orderId != null) {
            MonitorContext removed = monitoredOrders.remove(orderId);
            if (removed != null) {
                log.info("订单监听已停止 | orderId={}", orderId);
            }
        }
    }

    /**
     * 获取当前被监听的订单数量
     */
    public int getActiveMonitorCount() {
        return monitoredOrders.size();
    }

    // ==================== 内部类型 ====================

    /**
     * 监听上下文
     */
    private record MonitorContext(
            String orderId,
            String userId,
            Consumer<String> callback
    ) {
    }
}
