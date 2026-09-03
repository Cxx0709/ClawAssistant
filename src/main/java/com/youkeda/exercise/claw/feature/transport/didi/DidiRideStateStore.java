package com.youkeda.exercise.claw.feature.transport.didi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.feature.transport.didi.model.TaxiEstimateRequest;
import com.youkeda.exercise.claw.feature.transport.didi.model.TaxiEstimateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 滴滴打车状态存储（SQLite 持久化，按 userId 隔离）
 *
 * <p>管理打车流程状态，确保 {@code estimate → confirm → create_order} 的有序流转。
 * 状态持久化到 {@code didi_ride_state} 表（{@code user_id} 主键），服务重启后不丢失。
 * 按 userId 分片锁保证同一用户的状态流转原子性，不同用户互不阻塞。
 *
 * <p>状态机：
 * <pre>
 *   ESTIMATED        — 估价完成，等待用户确认
 *       ↓
 *   WAITING_CONFIRM  — 用户已确认（LLM 调用 confirm），等待创建订单
 *       ↓
 *   ORDER_CREATED    — 订单创建成功
 * </pre>
 *
 * <p>生命周期（批次 5 修复）：{@code saveEstimate} 一律覆盖旧状态（新估价 = 新流程，
 * 不再因 ORDER_CREATED 拒绝——否则下单后不 cancel 就永远无法再估价）；
 * 非终态（ESTIMATED/WAITING_CONFIRM）状态超过 {@value #STATE_TTL_MILLIS} 毫秒视为过期，
 * {@link #getRequired} 会拒绝继续。
 */
@Component
public class DidiRideStateStore {

    private static final Logger log = LoggerFactory.getLogger(DidiRideStateStore.class);

    /** 非终态状态的过期时间（毫秒）：超过视为估价失效，需重新估价 */
    private static final long STATE_TTL_MILLIS = 30 * 60 * 1000L;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    /** 按 userId 分片锁：保证同一用户状态流转原子性 */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public DidiRideStateStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        ensureTable();
    }

    private void ensureTable() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS didi_ride_state (
                user_id TEXT PRIMARY KEY,
                status TEXT NOT NULL,
                trace_id TEXT,
                order_id TEXT,
                estimate_request_json TEXT,
                estimate_response_json TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """);
    }

    private Object lockFor(String userId) {
        return locks.computeIfAbsent(userId, k -> new Object());
    }

    // ==================== 状态枚举 ====================

    /**
     * 打车流程状态
     */
    public enum RideStatus {
        /** 估价完成，等待用户确认 */
        ESTIMATED,
        /** 用户已确认，等待创建订单 */
        WAITING_CONFIRM,
        /** 订单已创建 */
        ORDER_CREATED
    }

    // ==================== 写操作 ====================

    /**
     * 保存估价结果，设置状态为 {@link RideStatus#ESTIMATED}。
     *
     * <p>一律覆盖旧状态（新估价 = 新流程）：不因 ORDER_CREATED 拒绝，
     * 否则下单后用户无法再次估价，只能先取消。
     *
     * @param userId   用户标识
     * @param request  估价请求参数
     * @param response 估价响应结果
     */
    public void saveEstimate(String userId, TaxiEstimateRequest request, TaxiEstimateResponse response) {
        synchronized (lockFor(userId)) {
            long now = System.currentTimeMillis();
            RideState state = new RideState(
                    request,
                    response,
                    response != null ? response.getTraceId() : null,
                    null, // orderId
                    RideStatus.ESTIMATED,
                    now
            );
            upsert(userId, state, now);
            log.info("状态更新 | userId={} | status=ESTIMATED | traceId={}",
                    userId, response != null ? response.getTraceId() : "null");
        }
    }

    /**
     * 确认订单（用户确认价格后调用），将状态从 {@link RideStatus#ESTIMATED} 转为
     * {@link RideStatus#WAITING_CONFIRM}。
     *
     * @param userId 用户标识
     * @throws IllegalStateException 如果状态不是 ESTIMATED
     */
    public void confirmBooking(String userId) {
        synchronized (lockFor(userId)) {
            RideState state = load(userId);
            if (state == null) {
                throw new IllegalStateException("未找到打车估价记录，请先调用 estimate");
            }
            if (state.status == RideStatus.ORDER_CREATED) {
                throw new IllegalStateException("订单已创建，无需重复确认");
            }
            if (state.status != RideStatus.ESTIMATED) {
                throw new IllegalStateException("当前状态不允许确认：期望 ESTIMATED，实际 " + state.status);
            }

            long now = System.currentTimeMillis();
            RideState next = new RideState(
                    state.estimateRequest,
                    state.estimateResponse,
                    state.traceId,
                    state.orderId,
                    RideStatus.WAITING_CONFIRM,
                    now
            );
            upsert(userId, next, now);
            log.info("状态更新 | userId={} | status=WAITING_CONFIRM | traceId={}", userId, state.traceId);
        }
    }

    /**
     * 保存订单 ID，更新状态为 {@link RideStatus#ORDER_CREATED}。
     *
     * @param userId  用户标识
     * @param orderId 订单 ID
     * @throws IllegalStateException 如果状态不是 WAITING_CONFIRM
     */
    public void saveOrder(String userId, String orderId) {
        synchronized (lockFor(userId)) {
            RideState state = load(userId);
            if (state == null) {
                throw new IllegalStateException("未找到打车估价记录，请先调用 estimate");
            }
            if (state.status != RideStatus.WAITING_CONFIRM) {
                throw new IllegalStateException("订单未获得用户确认：期望 WAITING_CONFIRM，实际 " + state.status
                        + "。请先等待用户确认再创建订单");
            }

            long now = System.currentTimeMillis();
            RideState next = new RideState(
                    state.estimateRequest,
                    state.estimateResponse,
                    state.traceId,
                    orderId,
                    RideStatus.ORDER_CREATED,
                    now
            );
            upsert(userId, next, now);
            log.info("状态更新 | userId={} | status=ORDER_CREATED | orderId={}", userId, orderId);
        }
    }

    /**
     * 清除状态（订单完成或取消后调用）
     *
     * @param userId 用户标识
     */
    public void clear(String userId) {
        synchronized (lockFor(userId)) {
            RideState state = load(userId);
            if (state != null) {
                log.info("状态清除 | userId={} | lastStatus={} | orderId={}",
                        userId, state.status, state.orderId);
            }
            jdbcTemplate.update("DELETE FROM didi_ride_state WHERE user_id = ?", userId);
        }
    }

    // ==================== 读操作 ====================

    /**
     * 获取当前状态
     *
     * @param userId 用户标识
     * @return 当前状态，无记录返回 null
     */
    public RideState get(String userId) {
        synchronized (lockFor(userId)) {
            return load(userId);
        }
    }

    /**
     * 获取当前状态，不存在或已过期时抛出异常
     *
     * @param userId 用户标识
     * @throws IllegalStateException 无记录，或非终态状态已超过 {@value #STATE_TTL_MILLIS} 毫秒
     */
    public RideState getRequired(String userId) {
        synchronized (lockFor(userId)) {
            RideState state = load(userId);
            if (state == null) {
                throw new IllegalStateException("未找到打车记录，请先调用 estimate");
            }
            // TTL：非终态估价超过 30 分钟视为失效，需重新估价
            if (state.status != RideStatus.ORDER_CREATED && state.ageMs() > STATE_TTL_MILLIS) {
                throw new IllegalStateException("估价已过期，请重新估价");
            }
            return state;
        }
    }

    // ==================== 内部类型 ====================

    /**
     * 打车过程的完整状态快照（不可变记录）
     */
    public record RideState(
            TaxiEstimateRequest estimateRequest,
            TaxiEstimateResponse estimateResponse,
            String traceId,
            String orderId,
            RideStatus status,
            long timestamp
    ) {
        /** 获取状态持续时长（毫秒） */
        public long ageMs() {
            return System.currentTimeMillis() - timestamp;
        }
    }

    // ==================== 内部方法 ====================

    private void upsert(String userId, RideState state, long now) {
        try {
            String requestJson = objectMapper.writeValueAsString(state.estimateRequest());
            String responseJson = objectMapper.writeValueAsString(state.estimateResponse());
            jdbcTemplate.update("""
                INSERT OR REPLACE INTO didi_ride_state
                    (user_id, status, trace_id, order_id, estimate_request_json,
                     estimate_response_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, userId,
                    state.status().name(),
                    state.traceId(),
                    state.orderId(),
                    requestJson,
                    responseJson,
                    now, now);
        } catch (Exception e) {
            log.error("保存打车状态失败 | userId={} | error={}", userId, e.getMessage(), e);
        }
    }

    private RideState load(String userId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT status, trace_id, order_id, estimate_request_json, "
                            + "estimate_response_json, updated_at "
                            + "FROM didi_ride_state WHERE user_id = ?", userId);
            if (rows.isEmpty()) {
                return null;
            }
            Map<String, Object> row = rows.get(0);
            String status = (String) row.get("status");
            String traceId = (String) row.get("trace_id");
            String orderId = (String) row.get("order_id");
            TaxiEstimateRequest request = parseJson(
                    (String) row.get("estimate_request_json"), TaxiEstimateRequest.class);
            TaxiEstimateResponse response = parseJson(
                    (String) row.get("estimate_response_json"), TaxiEstimateResponse.class);
            long timestamp = ((Number) row.get("updated_at")).longValue();
            return new RideState(request, response, traceId, orderId,
                    status != null ? RideStatus.valueOf(status) : null, timestamp);
        } catch (Exception e) {
            log.error("读取打车状态失败 | userId={} | error={}", userId, e.getMessage(), e);
            return null;
        }
    }

    private <T> T parseJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("反序列化打车状态失败 | type={} | error={}", type.getSimpleName(), e.getMessage());
            return null;
        }
    }
}
