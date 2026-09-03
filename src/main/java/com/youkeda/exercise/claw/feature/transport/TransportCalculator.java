package com.youkeda.exercise.claw.feature.transport;

import com.youkeda.exercise.claw.domain.transport.TransportOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 交通费用计算器
 *
 * <p>根据距离、人数计算各交通方式的费用、耗时、运力。
 * 所有计算由 Java 完成，不依赖 LLM。
 *
 * <p>运力规则：
 * <ul>
 *   <li><b>大巴</b>：50 座/辆，按人数自动计算车辆数</li>
 *   <li><b>自驾</b>：5 人/辆，按人数自动计算车辆数</li>
 *   <li><b>高铁/飞机</b>：无需车辆概念，按人头购票</li>
 * </ul>
 *
 * <p>计算公式：
 * <ul>
 *   <li><b>大巴</b>：包车单价 6元/公里 × 车数，人均 = 总价 / 人数</li>
 *   <li><b>自驾</b>：油费 = distanceKm / 100 × 8L × 油价 + 高速费 = distanceKm × 0.45，× 车数</li>
 *   <li><b>高铁</b>：二等座估算 0.46元/公里 + 20元市内交通，耗时 = 驾车时间 × 0.6 + 候车60分钟</li>
 *   <li><b>飞机</b>：仅 distanceKm > 800 时计算，机票估算 0.8元/公里 + 200燃油基建</li>
 * </ul>
 */
@Component
public class TransportCalculator {

    private static final Logger log = LoggerFactory.getLogger(TransportCalculator.class);

    // ==================== 大巴参数 ====================
    /** 大巴座位数 */
    private static final int BUS_SEATS = 50;
    /** 大巴包车单价（元/公里/辆） */
    private static final double BUS_UNIT_PRICE_PER_KM = 6.0;
    /** 大巴比自驾慢的比例 */
    private static final double BUS_SLOW_FACTOR = 1.2;

    // ==================== 自驾参数 ====================
    /** 小客车载客数 */
    private static final int CAR_SEATS = 5;
    /** 百公里油耗（升） */
    private static final double FUEL_CONSUMPTION_PER_100KM = 8.0;
    /** 油价（元/升） */
    private static final double FUEL_PRICE_PER_LITER = 8.5;
    /** 高速费估算（元/公里/辆） */
    private static final double HIGHWAY_TOLL_PER_KM = 0.45;

    // ==================== 高铁参数 ====================
    /** 高铁二等座单价估算（元/公里） */
    private static final double RAIL_UNIT_PRICE_PER_KM = 0.46;
    /** 市内交通费用估算（元/人） */
    private static final double RAIL_CITY_TRANSIT_COST = 20.0;
    /** 高铁速度系数（驾车时间的比例） */
    private static final double RAIL_SPEED_FACTOR = 0.6;
    /** 高铁候车+进出站附加时间（分钟） */
    private static final int RAIL_WAITING_MINUTES = 60;

    // ==================== 飞机参数 ====================
    /** 飞机适用最小距离（公里） */
    static final double FLIGHT_MIN_DISTANCE_KM = 800.0;
    /** 机票单价估算（元/公里） */
    private static final double FLIGHT_UNIT_PRICE_PER_KM = 0.8;
    /** 燃油附加+机场建设费（元/人） */
    private static final double FLIGHT_TAX_FEE = 200.0;
    /** 飞行时速（公里/小时，含起降） */
    private static final double FLIGHT_SPEED_KMH = 700.0;
    /** 机场前后准备+候机+提取行李时间（分钟） */
    private static final int FLIGHT_GROUND_MINUTES = 180;

    /**
     * 计算所有交通方式的费用
     *
     * @param distanceKm            驾车距离（公里）
     * @param drivingDurationMinutes 驾车耗时（分钟）
     * @param people                人数
     * @return 交通方式对比列表
     */
    public List<TransportOption> calculate(double distanceKm, int drivingDurationMinutes, int people) {
        log.info("交通费用计算 | distanceKm={} | drivingDurationMin={} | people={}",
                distanceKm, drivingDurationMinutes, people);

        List<TransportOption> options = new ArrayList<>();

        // 1. 大巴
        options.add(calculateBus(distanceKm, drivingDurationMinutes, people));

        // 2. 自驾
        options.add(calculateSelfDrive(distanceKm, drivingDurationMinutes, people));

        // 3. 高铁
        options.add(calculateHighSpeedRail(distanceKm, drivingDurationMinutes, people));

        // 4. 飞机（仅长距离）
        if (distanceKm >= FLIGHT_MIN_DISTANCE_KM) {
            options.add(calculateFlight(distanceKm, people));
        }

        return options;
    }

    /**
     * 大巴：50座/辆，按人数自动计算车辆数
     */
    private TransportOption calculateBus(double distanceKm, int drivingMinutes, int people) {
        int busCount = (int) Math.ceil((double) people / BUS_SEATS);
        double totalCost = distanceKm * BUS_UNIT_PRICE_PER_KM * busCount;
        double perPersonCost = totalCost / people;
        int busMinutes = (int) (drivingMinutes * BUS_SLOW_FACTOR);

        TransportOption option = new TransportOption();
        option.setType("bus");
        option.setTypeName("大巴");
        option.setTotalCost(Math.round(totalCost * 100.0) / 100.0);
        option.setPerPersonCost(Math.round(perPersonCost * 100.0) / 100.0);
        option.setDurationMinutes(busMinutes);
        option.setDurationText(formatDuration(busMinutes));
        option.setDistanceKm(distanceKm);
        option.setVehicleCount(busCount);
        option.setCapacity(busCount + "辆×" + BUS_SEATS + "座（共" + (busCount * BUS_SEATS) + "座）");
        option.setAdvantage(buildBusAdvantage(people, busCount));
        return option;
    }

    /**
     * 自驾：5人/车，按人数自动计算车辆数
     */
    private TransportOption calculateSelfDrive(double distanceKm, int drivingMinutes, int people) {
        int carCount = (int) Math.ceil((double) people / CAR_SEATS);
        double fuelCost = distanceKm / 100.0 * FUEL_CONSUMPTION_PER_100KM * FUEL_PRICE_PER_LITER;
        double tollCost = distanceKm * HIGHWAY_TOLL_PER_KM;
        double costPerCar = fuelCost + tollCost;
        double totalCost = costPerCar * carCount;
        double perPersonCost = totalCost / people;

        TransportOption option = new TransportOption();
        option.setType("self_drive");
        option.setTypeName("自驾");
        option.setTotalCost(Math.round(totalCost * 100.0) / 100.0);
        option.setPerPersonCost(Math.round(perPersonCost * 100.0) / 100.0);
        option.setDurationMinutes(drivingMinutes);
        option.setDurationText(formatDuration(drivingMinutes));
        option.setDistanceKm(distanceKm);
        option.setVehicleCount(carCount);
        option.setCapacity(carCount + "辆×" + CAR_SEATS + "座（共" + (carCount * CAR_SEATS) + "座）");
        option.setAdvantage(buildSelfDriveAdvantage(people, carCount));
        return option;
    }

    /**
     * 高铁/火车：二等座估算，无需车辆概念
     */
    private TransportOption calculateHighSpeedRail(double distanceKm, int drivingMinutes, int people) {
        double ticketCost = distanceKm * RAIL_UNIT_PRICE_PER_KM;
        double cityTransit = RAIL_CITY_TRANSIT_COST;
        double totalCost = (ticketCost + cityTransit) * people;
        double perPersonCost = totalCost / people;
        int railMinutes = (int) (drivingMinutes * RAIL_SPEED_FACTOR) + RAIL_WAITING_MINUTES;

        TransportOption option = new TransportOption();
        option.setType("high_speed_rail");
        option.setTypeName("高铁/火车");
        option.setTotalCost(Math.round(totalCost * 100.0) / 100.0);
        option.setPerPersonCost(Math.round(perPersonCost * 100.0) / 100.0);
        option.setDurationMinutes(railMinutes);
        option.setDurationText(formatDuration(railMinutes));
        option.setDistanceKm(distanceKm);
        option.setVehicleCount(0);
        option.setCapacity(people + "人（按人购票）");
        option.setAdvantage(buildRailAdvantage(distanceKm));
        return option;
    }

    /**
     * 飞机：仅远距离计算，无需车辆概念
     */
    private TransportOption calculateFlight(double distanceKm, int people) {
        double ticketCost = distanceKm * FLIGHT_UNIT_PRICE_PER_KM + FLIGHT_TAX_FEE;
        double totalCost = ticketCost * people;
        double perPersonCost = totalCost / people;
        int flightMinutes = (int) (distanceKm / FLIGHT_SPEED_KMH * 60) + FLIGHT_GROUND_MINUTES;

        TransportOption option = new TransportOption();
        option.setType("flight");
        option.setTypeName("飞机");
        option.setTotalCost(Math.round(totalCost * 100.0) / 100.0);
        option.setPerPersonCost(Math.round(perPersonCost * 100.0) / 100.0);
        option.setDurationMinutes(flightMinutes);
        option.setDurationText(formatDuration(flightMinutes));
        option.setDistanceKm(distanceKm);
        option.setVehicleCount(0);
        option.setCapacity(people + "人（按人购票）");
        option.setAdvantage("速度快，适合长途出行，但需提前到机场，行李受限");
        return option;
    }

    // ==================== 优势描述构建 ====================

    private String buildBusAdvantage(int people, int busCount) {
        if (busCount == 1) {
            return "1辆大巴直达，统一出发、便于管理，车上可组织团建互动，无需换乘";
        }
        return String.format("%d辆大巴车队统一出发，便于人数较多的团队管理，车上可组织团建互动", busCount);
    }

    private String buildSelfDriveAdvantage(int people, int carCount) {
        if (carCount == 1) {
            return "1辆车即可出行，灵活性最高，随时出发，路线自由安排";
        }
        return String.format("%d辆车组队出行，出发时间灵活，可沿途停留，适合小团体", carCount);
    }

    private String buildRailAdvantage(double distanceKm) {
        if (distanceKm <= 300) {
            return "准时高效，乘坐舒适，中短途最优选择";
        } else if (distanceKm <= 800) {
            return "比自驾更省心，比飞机更经济，准点率高";
        }
        return "高铁网络发达，准点率高，乘坐体验好";
    }

    // ==================== 公开工具方法 ====================

    /**
     * 格式化耗时为中文描述
     */
    public static String formatDuration(int totalMinutes) {
        int hours = totalMinutes / 60;
        int minutes = totalMinutes % 60;
        if (hours > 0 && minutes > 0) {
            return hours + "小时" + minutes + "分钟";
        } else if (hours > 0) {
            return hours + "小时";
        } else {
            return minutes + "分钟";
        }
    }
}