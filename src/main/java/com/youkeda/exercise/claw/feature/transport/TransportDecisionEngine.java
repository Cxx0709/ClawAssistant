package com.youkeda.exercise.claw.feature.transport;

import com.youkeda.exercise.claw.domain.transport.TransportOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 交通方式推荐决策引擎
 *
 * <p>基于人数、距离、成本、时间、管理难度等多维度给出推荐结论。
 * 规则纯 Java 实现，不依赖 LLM。
 *
 * <p>推荐规则（按优先级）：
 * <ol>
 *   <li>人数 >= 30 → 优先大巴（即便高铁更快，大巴人均成本和管理便利性优势大）</li>
 *   <li>人数 <= 5 且距离 <= 300km → 优先自驾（灵活性最高）</li>
 *   <li>6-29 人 → 综合评分：成本 + 时间 + 管理难度</li>
 *   <li>距离 > 800km → 增加飞机比较，视预算决定</li>
 *   <li>其他情况 → 推荐高铁（性价比最优）</li>
 * </ol>
 *
 * <p>综合评分维度（6-29人场景）：
 * <ul>
 *   <li>人均成本权重 40%</li>
 *   <li>耗时权重 30%</li>
 *   <li>管理便利性权重 30%（大巴统一管理 > 高铁各自行动 > 自驾分散）</li>
 * </ul>
 */
@Component
public class TransportDecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(TransportDecisionEngine.class);

    /** 大巴推荐人数阈值 */
    private static final int BUS_RECOMMEND_THRESHOLD = 30;

    /** 自驾推荐人数上限 */
    private static final int SELF_DRIVE_MAX_PEOPLE = 5;

    /** 自驾推荐距离上限（公里） */
    private static final double SELF_DRIVE_MAX_DISTANCE_KM = 300.0;

    /** 飞机考虑距离阈值（公里） */
    private static final double FLIGHT_CONSIDER_DISTANCE_KM = 800.0;

    /** 短途距离阈值（公里） */
    private static final double SHORT_DISTANCE_KM = 100.0;

    /** 综合评分权重：人均成本 */
    private static final double WEIGHT_COST = 0.4;
    /** 综合评分权重：耗时 */
    private static final double WEIGHT_TIME = 0.3;
    /** 综合评分权重：管理便利性 */
    private static final double WEIGHT_MANAGEMENT = 0.3;

    /**
     * 给出推荐结论和推荐理由
     *
     * @param distanceKm 驾车距离（公里）
     * @param people     出行人数
     * @param options    所有交通方式对比列表
     * @param budget     可选预算
     * @return DecisionResult 包含推荐文本和推荐理由
     */
    public DecisionResult decide(double distanceKm, int people, List<TransportOption> options, Integer budget) {
        log.info("交通推荐决策 | distanceKm={} | people={} | budget={}", distanceKm, people, budget);

        // 找到人均最低方案
        TransportOption cheapest = options.stream()
                .min(Comparator.comparingDouble(TransportOption::getPerPersonCost))
                .orElse(null);

        // 找到最快方案
        TransportOption fastest = options.stream()
                .min(Comparator.comparingInt(TransportOption::getDurationMinutes))
                .orElse(null);

        // 大巴和高铁方案（用于后续比价）
        TransportOption busOption = findOption(options, "bus");
        TransportOption railOption = findOption(options, "high_speed_rail");
        TransportOption driveOption = findOption(options, "self_drive");
        TransportOption flightOption = findOption(options, "flight");

        String recommendation;
        String reason;

        // ========== 规则1：30人以上 → 大巴优先 ==========
        if (people >= BUS_RECOMMEND_THRESHOLD) {
            recommendation = "🚌 推荐「大巴」出行";
            reason = buildBusPriorityReason(people, distanceKm, busOption, railOption, fastest);

        // ========== 规则2：5人以下短途 → 自驾优先 ==========
        } else if (people <= SELF_DRIVE_MAX_PEOPLE && distanceKm <= SELF_DRIVE_MAX_DISTANCE_KM) {
            recommendation = "🚗 推荐「自驾」出行";
            reason = buildSelfDrivePriorityReason(people, distanceKm, driveOption, railOption);

        // ========== 规则3：6-29人 → 综合评分 ==========
        } else if (people > SELF_DRIVE_MAX_PEOPLE && people < BUS_RECOMMEND_THRESHOLD) {
            ScoredOption scored = scoreOptions(options, people, budget);
            recommendation = scored.label + "「" + scored.option.getTypeName() + "」出行";
            reason = scored.reason;

        // ========== 规则4：远距离 → 飞机+高铁比较 ==========
        } else if (distanceKm >= FLIGHT_CONSIDER_DISTANCE_KM) {
            if (budget != null && budget > 0 && flightOption != null) {
                double flightPerPerson = flightOption.getPerPersonCost();
                double railPerPerson = railOption != null ? railOption.getPerPersonCost() : Double.MAX_VALUE;
                if (flightPerPerson <= railPerPerson * 1.3 && flightPerPerson * people <= budget) {
                    recommendation = "✈️ 推荐「飞机」出行";
                    reason = buildFlightPriorityReason(distanceKm, people, flightOption, railOption, budget);
                } else {
                    recommendation = "🚄 推荐「高铁」出行";
                    reason = buildRailPriorityReason(distanceKm, people, railOption, flightOption, budget, "长距离");
                }
            } else {
                recommendation = "🚄 推荐「高铁」出行";
                reason = buildRailPriorityReason(distanceKm, people, railOption, flightOption, budget, "长距离");
            }

        // ========== 规则5：默认 → 高铁 ==========
        } else {
            recommendation = "🚄 推荐「高铁/火车」出行";
            reason = buildRailPriorityReason(distanceKm, people, railOption, flightOption, budget, "中距离");
        }

        // 拼接补充建议
        String fullRecommendation = recommendation + "\n\n" + buildSupplement(
                distanceKm, people, cheapest, fastest, budget, options);

        return new DecisionResult(fullRecommendation, reason);
    }

    // ==================== 综合评分（6-29人场景） ====================

    /**
     * 对大巴、自驾、高铁三个方案做综合评分
     */
    private ScoredOption scoreOptions(List<TransportOption> options, int people, Integer budget) {
        List<TransportOption> candidates = options.stream()
                .filter(o -> !"flight".equals(o.getType()))
                .collect(Collectors.toList());

        // 计算各项的 min/max 用于归一化（成本越低越好，时间越短越好，管理分越高越好）
        double minCost = candidates.stream().mapToDouble(TransportOption::getPerPersonCost).min().orElse(1);
        double maxCost = candidates.stream().mapToDouble(TransportOption::getPerPersonCost).max().orElse(1);
        double costRange = Math.max(maxCost - minCost, 1);

        int minTime = candidates.stream().mapToInt(TransportOption::getDurationMinutes).min().orElse(1);
        int maxTime = candidates.stream().mapToInt(TransportOption::getDurationMinutes).max().orElse(1);
        double timeRange = Math.max(maxTime - minTime, 1);

        Map<String, Double> scores = new LinkedHashMap<>();
        for (TransportOption opt : candidates) {
            // 成本分：0-1，越低越好
            double costScore = maxCost > minCost
                    ? 1 - (opt.getPerPersonCost() - minCost) / costRange
                    : 1.0;
            // 时间分：0-1，越快越好
            double timeScore = maxTime > minTime
                    ? 1 - (opt.getDurationMinutes() - minTime) / timeRange
                    : 1.0;
            // 管理分：大巴=1.0, 高铁=0.6, 自驾=0.2（人数越多自驾管理越难）
            double mgmtScore = switch (opt.getType()) {
                case "bus" -> 1.0;
                case "high_speed_rail" -> 0.6;
                default -> Math.max(0.1, 0.5 - people * 0.01); // 人越多自驾管理分越低
            };

            double total = costScore * WEIGHT_COST + timeScore * WEIGHT_TIME + mgmtScore * WEIGHT_MANAGEMENT;
            scores.put(opt.getType(), total);
        }

        // 最高分方案
        String bestType = scores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("high_speed_rail");

        TransportOption best = candidates.stream()
                .filter(o -> o.getType().equals(bestType))
                .findFirst().orElse(null);

        TransportOption railOpt = findOption(candidates, "high_speed_rail");
        TransportOption busOpt = findOption(candidates, "bus");
        TransportOption driveOpt = findOption(candidates, "self_drive");

        String emoji = switch (bestType) {
            case "bus" -> "🚌 推荐";
            case "self_drive" -> "🚗 推荐";
            default -> "🚄 推荐";
        };

        String reason = buildCompositeReason(people, distanceKm(driveOpt), best, railOpt, busOpt, scores, budget);

        return new ScoredOption(best, emoji, reason);
    }

    // ==================== 推荐理由构建 ====================

    private String buildBusPriorityReason(int people, double distanceKm,
                                           TransportOption bus, TransportOption rail, TransportOption fastest) {
        StringBuilder sb = new StringBuilder();
        sb.append("团队").append(people).append("人规模较大（≥30人），");
        if (bus != null) {
            sb.append("大巴人均仅").append(String.format("%.0f", bus.getPerPersonCost())).append("元");
            sb.append("，共需").append(bus.getVehicleCount()).append("辆车。");
        }
        if (rail != null) {
            sb.append("虽然高铁耗时更短，但大巴人均成本更低、");
        }
        sb.append("统一出发便于管理，车上可开展团建互动，最适合团队出行场景。");
        if (fastest != null && !"bus".equals(fastest.getType())) {
            sb.append("若对时间要求高，也可考虑「").append(fastest.getTypeName()).append("」作为备选。");
        }
        return sb.toString();
    }

    private String buildSelfDrivePriorityReason(int people, double distanceKm,
                                                 TransportOption drive, TransportOption rail) {
        StringBuilder sb = new StringBuilder();
        sb.append(people).append("人小团队短途出行（").append(String.format("%.0f", distanceKm)).append("公里），");
        if (drive != null) {
            sb.append("自驾仅需").append(drive.getVehicleCount()).append("辆车");
            sb.append("，人均").append(String.format("%.0f", drive.getPerPersonCost())).append("元，");
        }
        sb.append("灵活性最高，随时出发、路线自由，无需受时刻表限制。");
        if (rail != null && rail.getPerPersonCost() < (drive != null ? drive.getPerPersonCost() : Double.MAX_VALUE)) {
            sb.append("若在意成本，高铁人均更低，也可考虑。");
        }
        return sb.toString();
    }

    private String buildRailPriorityReason(double distanceKm, int people,
                                            TransportOption rail, TransportOption flight,
                                            Integer budget, String sceneLabel) {
        StringBuilder sb = new StringBuilder();
        sb.append(sceneLabel).append("出行（").append(String.format("%.0f", distanceKm)).append("公里），");
        if (rail != null) {
            sb.append("高铁人均约").append(String.format("%.0f", rail.getPerPersonCost())).append("元，");
            sb.append("耗时约").append(rail.getDurationText()).append("。");
        }
        sb.append("高铁准点率高、乘坐舒适，相比自驾更省心无需疲劳驾驶");
        if (flight != null) {
            sb.append("，相比飞机更经济且不受天气影响");
        }
        sb.append("，是综合最优选择。");
        if (budget != null && budget > 0 && rail != null) {
            double total = rail.getPerPersonCost() * people;
            if (total <= budget) {
                sb.append("总费用在预算范围内（").append(String.format("%.0f", total)).append(" ≤ ").append(budget).append("元）✓");
            } else {
                sb.append("⚠ 总费用").append(String.format("%.0f", total)).append("元超出预算").append(budget).append("元，建议调整方案。");
            }
        }
        return sb.toString();
    }

    private String buildFlightPriorityReason(double distanceKm, int people,
                                              TransportOption flight, TransportOption rail, Integer budget) {
        StringBuilder sb = new StringBuilder();
        sb.append("距离").append(String.format("%.0f", distanceKm)).append("公里较远，");
        if (flight != null) {
            sb.append("飞机人均约").append(String.format("%.0f", flight.getPerPersonCost())).append("元，");
            sb.append("耗时约").append(flight.getDurationText()).append("，大幅节省时间。");
        }
        if (rail != null) {
            sb.append("高铁人均约").append(String.format("%.0f", rail.getPerPersonCost())).append("元，");
        }
        sb.append("飞机时间优势明显，且人均费用在预算范围内，是长途出行效率最优选择。");
        return sb.toString();
    }

    /**
     * 6-29人综合评分场景的推荐理由
     */
    private String buildCompositeReason(int people, double distanceKm,
                                         TransportOption best, TransportOption rail,
                                         TransportOption bus, Map<String, Double> scores,
                                         Integer budget) {
        StringBuilder sb = new StringBuilder();
        sb.append(people).append("人团队出行（").append(String.format("%.0f", distanceKm)).append("公里），");
        sb.append("从成本、时间、管理便利性三个维度综合评分：\n");

        // 列举各方案得分
        List<String> scoreLines = new ArrayList<>();
        if (bus != null && scores.containsKey("bus")) {
            scoreLines.add("• 大巴：人均" + String.format("%.0f", bus.getPerPersonCost()) + "元，"
                    + bus.getDurationText() + "，统一管理（综合分 " + String.format("%.2f", scores.get("bus")) + "）");
        }
        if (rail != null && scores.containsKey("high_speed_rail")) {
            scoreLines.add("• 高铁：人均" + String.format("%.0f", rail.getPerPersonCost()) + "元，"
                    + rail.getDurationText() + "，各自行动（综合分 " + String.format("%.2f", scores.get("high_speed_rail")) + "）");
        }
        TransportOption drive = findOption(List.of(best), "self_drive");
        if (scores.containsKey("self_drive")) {
            scoreLines.add("• 自驾：需多辆车分散管理（综合分 " + String.format("%.2f", scores.get("self_drive")) + "）");
        }
        sb.append(String.join("\n", scoreLines));
        sb.append("\n\n");

        sb.append(best != null ? best.getTypeName() : "高铁").append("综合得分最高。");
        if (best != null && "bus".equals(best.getType())) {
            sb.append("大巴适合团队统一管理，人均成本低，团建场景优势明显。");
        } else if (best != null && "high_speed_rail".equals(best.getType())) {
            sb.append("高铁在成本和时间之间取得良好平衡，乘坐舒适，适合中等规模团队。");
        }

        if (budget != null && budget > 0 && best != null) {
            double total = best.getPerPersonCost() * people;
            if (total <= budget) {
                sb.append("总费用在预算范围内 ✓");
            } else {
                sb.append("⚠ 超出预算，建议调整方案。");
            }
        }
        return sb.toString();
    }

    // ==================== 补充建议 ====================

    private String buildSupplement(double distanceKm, int people,
                                    TransportOption cheapest, TransportOption fastest,
                                    Integer budget, List<TransportOption> options) {
        StringBuilder sb = new StringBuilder();
        sb.append("💡 补充建议：");

        if (cheapest != null) {
            sb.append("\n• 人均最低方案：").append(cheapest.getTypeName())
                    .append("，人均").append(String.format("%.0f", cheapest.getPerPersonCost())).append("元");
        }
        if (fastest != null) {
            sb.append("\n• 最快方案：").append(fastest.getTypeName())
                    .append("，约").append(fastest.getDurationText());
        }

        // 运力提示
        for (TransportOption opt : options) {
            if (opt.getVehicleCount() > 1) {
                sb.append("\n• ").append(opt.getTypeName()).append("需").append(opt.getVehicleCount()).append("辆/车");
            }
        }

        // 预算分析
        if (budget != null && budget > 0 && cheapest != null) {
            double totalNeeded = cheapest.getPerPersonCost() * people;
            if (totalNeeded <= budget) {
                sb.append("\n• 预算充足：最经济方案的人均费用在预算范围内 ✓");
            } else {
                sb.append("\n• 预算提醒：人均费用超出预算，建议增加预算或减少人数");
            }
        }

        // 距离提示
        if (distanceKm <= SHORT_DISTANCE_KM) {
            sb.append("\n• 短途出行（").append(String.format("%.0f", distanceKm)).append("公里）：自驾或大巴最方便，无需考虑高铁飞机");
        } else if (distanceKm >= FLIGHT_CONSIDER_DISTANCE_KM) {
            sb.append("\n• 长途出行（").append(String.format("%.0f", distanceKm)).append("公里）：建议优先考虑高铁或飞机，避免长途驾驶疲劳");
        }

        // 人数提示
        if (people >= BUS_RECOMMEND_THRESHOLD) {
            sb.append("\n• 大团队（").append(people).append("人）：大巴统一管理最省心，如需分批出发可搭配高铁");
        } else if (people <= SELF_DRIVE_MAX_PEOPLE) {
            sb.append("\n• 小团队（").append(people).append("人）：灵活度优先，自驾或高铁按个人偏好选择");
        }

        sb.append("\n\n📊 以上为基于距离、人数、成本的算法推荐，请根据团队具体情况（出发地分布、时间安排、成员偏好）灵活选择。");
        return sb.toString();
    }

    // ==================== 工具方法 ====================

    private TransportOption findOption(List<TransportOption> options, String type) {
        return options.stream()
                .filter(o -> o.getType().equals(type))
                .findFirst()
                .orElse(null);
    }

    private double distanceKm(TransportOption opt) {
        return opt != null ? opt.getDistanceKm() : 0;
    }

    /**
     * 决策结果
     */
    public static class DecisionResult {
        /** 面向用户的推荐文本（含补充建议） */
        public final String recommendation;
        /** 详细的推荐理由 */
        public final String reason;

        public DecisionResult(String recommendation, String reason) {
            this.recommendation = recommendation;
            this.reason = reason;
        }
    }

    /**
     * 评分结果
     */
    private static class ScoredOption {
        final TransportOption option;
        final String label;
        final String reason;

        ScoredOption(TransportOption option, String label, String reason) {
            this.option = option;
            this.label = label;
            this.reason = reason;
        }
    }
}