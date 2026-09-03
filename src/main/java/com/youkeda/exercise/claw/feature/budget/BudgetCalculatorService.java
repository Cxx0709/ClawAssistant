package com.youkeda.exercise.claw.feature.budget;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 根据具体方案项目、数量和单价核算一个或多个方案的预计成本。 */
@Service
public class BudgetCalculatorService {

    public PlanCostResult calculate(BatchPlanCostRequest request) {
        PlanCostResult result = new PlanCostResult();
        if (request == null) {
            result.setStatus("INVALID_ARGUMENT");
            result.getWarnings().add("请求不能为空。");
            return result;
        }

        result.setCity(request.getCity());
        result.setHeadcount(request.getHeadcount());
        result.setDays(request.getDays());

        if (request.getHeadcount() == null || request.getHeadcount() <= 0) {
            result.setStatus("INVALID_ARGUMENT");
            result.getWarnings().add("headcount 必须是大于0的整数。");
            return result;
        }
        if (request.getDays() == null || request.getDays() <= 0) {
            result.setStatus("INVALID_ARGUMENT");
            result.getWarnings().add("days 必须是大于0的整数。");
            return result;
        }

        int nights = request.getNights() != null
                ? request.getNights()
                : Math.max(request.getDays() - 1, 0);
        if (nights < 0) {
            result.setStatus("INVALID_ARGUMENT");
            result.getWarnings().add("nights 不能小于0。");
            return result;
        }
        result.setNights(nights);

        BigDecimal contingencyRate = request.getContingencyRate() == null
                ? new BigDecimal("0.10")
                : request.getContingencyRate();
        if (contingencyRate.compareTo(BigDecimal.ZERO) < 0
                || contingencyRate.compareTo(BigDecimal.ONE) > 0) {
            result.setStatus("INVALID_ARGUMENT");
            result.getWarnings().add("contingency_rate 必须在0到1之间。");
            return result;
        }

        BigDecimal targetBudget = resolveTargetBudget(request, result);
        if ("BUDGET_CONFLICT".equals(result.getStatus())) return result;
        if (targetBudget == null) {
            result.setStatus("MISSING_INFORMATION");
            result.getWarnings().add("必须提供总预算上限或人均预算上限，才能比较方案成本。");
            return result;
        }

        if (request.getPlans() == null || request.getPlans().isEmpty()) {
            result.setStatus("MISSING_INFORMATION");
            result.getWarnings().add("至少需要提供一个待核算方案。");
            return result;
        }

        for (PlanCostOption plan : request.getPlans()) {
            result.getPlans().add(calculateOption(
                    plan, request.getHeadcount(), request.getDays(), nights,
                    contingencyRate, targetBudget));
        }

        boolean allComplete = result.getPlans().stream()
                .allMatch(plan -> plan.getCostStatus() == OptionCostStatus.SUCCESS);
        result.setStatus(allComplete ? "SUCCESS" : "PARTIAL");
        buildComparison(result);
        return result;
    }

    private OptionCostResult calculateOption(PlanCostOption plan, int headcount, int days, int nights,
                                             BigDecimal contingencyRate, BigDecimal targetBudget) {
        OptionCostResult result = new OptionCostResult();
        if (plan == null) {
            result.setCostStatus(OptionCostStatus.PARTIAL);
            result.getMissingPriceItems().add("方案内容");
            return result;
        }

        result.setPlanId(plan.getPlanId());
        result.setPlanName(plan.getPlanName());
        result.setPlanVersion(Math.max(plan.getPlanVersion(), 1));
        result.setTargetBudget(money(targetBudget));

        BigDecimal subtotalMin = BigDecimal.ZERO;
        BigDecimal subtotalMax = BigDecimal.ZERO;
        Map<String, BigDecimal[]> categoryTotals = new LinkedHashMap<>();

        if (plan.getItems() == null || plan.getItems().isEmpty()) {
            result.getMissingPriceItems().add("方案费用项目");
        } else {
            for (PlanCostItem item : plan.getItems()) {
                CostItemResult calculated = calculateItem(item, headcount, days, nights, result);
                if (calculated == null) continue;
                result.getItems().add(calculated);
                subtotalMin = subtotalMin.add(calculated.getMinAmount());
                subtotalMax = subtotalMax.add(calculated.getMaxAmount());
                String category = blank(calculated.getCategory()) ? "OTHER" : calculated.getCategory();
                BigDecimal[] totals = categoryTotals.computeIfAbsent(category,
                        ignored -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                totals[0] = totals[0].add(calculated.getMinAmount());
                totals[1] = totals[1].add(calculated.getMaxAmount());
            }
        }

        result.setKnownSubtotalMin(money(subtotalMin));
        result.setKnownSubtotalMax(money(subtotalMax));
        categoryTotals.forEach((category, totals) ->
                result.getBreakdown().add(new CostBreakdown(
                        category, money(totals[0]), money(totals[1]))));

        if (!result.getMissingPriceItems().isEmpty()) {
            result.setCostStatus(OptionCostStatus.PARTIAL);
            result.setBudgetStatus(BudgetStatus.INDETERMINATE);
            result.getWarnings().add("存在缺失价格，当前金额仅代表已知费用，不能作为完整总价。");
            return result;
        }

        BigDecimal contingencyMin = subtotalMin.multiply(contingencyRate);
        BigDecimal contingencyMax = subtotalMax.multiply(contingencyRate);
        BigDecimal totalMin = subtotalMin.add(contingencyMin);
        BigDecimal totalMax = subtotalMax.add(contingencyMax);

        result.setContingencyMin(money(contingencyMin));
        result.setContingencyMax(money(contingencyMax));
        result.setEstimatedTotalMin(money(totalMin));
        result.setEstimatedTotalMax(money(totalMax));
        result.setPerPersonMin(money(totalMin.divide(
                BigDecimal.valueOf(headcount), 8, RoundingMode.HALF_UP)));
        result.setPerPersonMax(money(totalMax.divide(
                BigDecimal.valueOf(headcount), 8, RoundingMode.HALF_UP)));
        result.setCostStatus(OptionCostStatus.SUCCESS);
        applyBudgetComparison(result, targetBudget);
        return result;
    }

    private CostItemResult calculateItem(PlanCostItem item, int headcount, int days, int nights,
                                         OptionCostResult optionResult) {
        String name = item != null && !blank(item.getItemName())
                ? item.getItemName() : "未命名费用项目";
        if (item == null || item.getPriceStatus() == PriceStatus.MISSING) {
            optionResult.getMissingPriceItems().add(name);
            return null;
        }
        if (item.getPricingMode() == null) {
            optionResult.getMissingPriceItems().add(name + "（缺少计费方式）");
            return null;
        }

        BigDecimal minPrice = item.getUnitPrice() != null
                ? item.getUnitPrice() : item.getMinUnitPrice();
        BigDecimal maxPrice = item.getUnitPrice() != null
                ? item.getUnitPrice() : item.getMaxUnitPrice();
        if (minPrice == null && maxPrice != null) minPrice = maxPrice;
        if (maxPrice == null && minPrice != null) maxPrice = minPrice;
        if (minPrice == null || maxPrice == null) {
            optionResult.getMissingPriceItems().add(name);
            return null;
        }
        if (minPrice.compareTo(BigDecimal.ZERO) < 0 || maxPrice.compareTo(BigDecimal.ZERO) < 0
                || minPrice.compareTo(maxPrice) > 0) {
            optionResult.getMissingPriceItems().add(name + "（价格区间无效）");
            return null;
        }

        BigDecimal multiplier = multiplier(item, headcount, days, nights);
        if (multiplier == null || multiplier.compareTo(BigDecimal.ZERO) < 0) {
            optionResult.getMissingPriceItems().add(name + "（数量或容量无效）");
            return null;
        }

        CostItemResult result = new CostItemResult();
        result.setCategory(item.getCategory());
        result.setItemName(name);
        result.setPricingMode(item.getPricingMode());
        result.setMultiplier(multiplier.stripTrailingZeros());
        result.setMinUnitPrice(money(minPrice));
        result.setMaxUnitPrice(money(maxPrice));
        result.setMinAmount(money(minPrice.multiply(multiplier)));
        result.setMaxAmount(money(maxPrice.multiply(multiplier)));
        result.setPriceStatus(item.getPriceStatus());
        result.setPriceSource(item.getPriceSource());
        result.setNotes(item.getNotes());
        if (blank(item.getPriceSource())) {
            optionResult.getWarnings().add(name + "缺少价格来源，最终方案必须标记为待确认。");
        }
        return result;
    }

    private BigDecimal multiplier(PlanCostItem item, int headcount, int days, int nights) {
        int people = item.getApplicableHeadcount() != null
                ? item.getApplicableHeadcount() : headcount;
        if (people < 0) return null;

        return switch (item.getPricingMode()) {
            case FIXED -> BigDecimal.ONE;
            case PER_PERSON -> BigDecimal.valueOf(people);
            case PER_PERSON_PER_DAY -> BigDecimal.valueOf((long) people * days);
            case PER_PERSON_PER_OCCURRENCE -> {
                if (item.getOccurrences() == null || item.getOccurrences() < 0) yield null;
                yield BigDecimal.valueOf((long) people * item.getOccurrences());
            }
            case PER_ROOM_PER_NIGHT -> {
                BigDecimal rooms = quantityOrCapacity(item, people);
                yield rooms == null ? null : rooms.multiply(BigDecimal.valueOf(nights));
            }
            case PER_VEHICLE, PER_TABLE -> quantityOrCapacity(item, people);
            case PER_UNIT -> positiveOrZero(item.getQuantity()) ? item.getQuantity() : null;
        };
    }

    private BigDecimal quantityOrCapacity(PlanCostItem item, int people) {
        if (positiveOrZero(item.getQuantity())) return item.getQuantity();
        if (item.getCapacity() == null || item.getCapacity() <= 0) return null;
        long units = (people + (long) item.getCapacity() - 1L) / item.getCapacity();
        return BigDecimal.valueOf(units);
    }

    private BigDecimal resolveTargetBudget(BatchPlanCostRequest request, PlanCostResult result) {
        BigDecimal total = request.getTargetTotalBudget();
        BigDecimal perPerson = request.getTargetPerPersonBudget();
        if (total != null && total.compareTo(BigDecimal.ZERO) <= 0) total = null;
        if (perPerson != null && perPerson.compareTo(BigDecimal.ZERO) <= 0) perPerson = null;

        if (total != null && perPerson != null) {
            BigDecimal calculated = perPerson.multiply(BigDecimal.valueOf(request.getHeadcount()));
            if (calculated.subtract(total).abs().compareTo(new BigDecimal("0.01")) > 0) {
                result.setStatus("BUDGET_CONFLICT");
                result.getWarnings().add("总预算上限与人均预算上限不一致，请确认以哪个为准。");
                return null;
            }
        }
        return total != null ? total
                : perPerson != null ? perPerson.multiply(BigDecimal.valueOf(request.getHeadcount()))
                : null;
    }

    private void applyBudgetComparison(OptionCostResult result, BigDecimal targetBudget) {
        if (targetBudget == null) {
            result.setBudgetStatus(BudgetStatus.NO_LIMIT);
            return;
        }
        BigDecimal min = result.getEstimatedTotalMin();
        BigDecimal max = result.getEstimatedTotalMax();
        BigDecimal overrunMin = min.subtract(targetBudget).max(BigDecimal.ZERO);
        BigDecimal overrunMax = max.subtract(targetBudget).max(BigDecimal.ZERO);
        result.setOverrunMin(money(overrunMin));
        result.setOverrunMax(money(overrunMax));
        result.setOverrunRateMax(targetBudget.signum() == 0 ? null
                : overrunMax.divide(targetBudget, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP));

        if (max.compareTo(targetBudget) <= 0) {
            result.setBudgetStatus(BudgetStatus.WITHIN_BUDGET);
        } else if (min.compareTo(targetBudget) > 0) {
            result.setBudgetStatus(BudgetStatus.OVER_BUDGET);
        } else {
            result.setBudgetStatus(BudgetStatus.POSSIBLY_OVER_BUDGET);
        }
    }

    private void buildComparison(PlanCostResult result) {
        List<OptionCostResult> complete = new ArrayList<>();
        for (OptionCostResult plan : result.getPlans()) {
            if (plan.getBudgetStatus() == BudgetStatus.WITHIN_BUDGET) {
                result.getWithinBudgetPlans().add(plan.getPlanId());
            } else if (plan.getBudgetStatus() == BudgetStatus.OVER_BUDGET
                    || plan.getBudgetStatus() == BudgetStatus.POSSIBLY_OVER_BUDGET) {
                result.getOverBudgetPlans().add(plan.getPlanId());
            }
            if (plan.getCostStatus() == OptionCostStatus.SUCCESS) complete.add(plan);
        }
        complete.stream().min(Comparator.comparing(OptionCostResult::getEstimatedTotalMax))
                .ifPresent(plan -> result.setLowestCostPlan(plan.getPlanId()));
        complete.stream().max(Comparator.comparing(OptionCostResult::getEstimatedTotalMax))
                .ifPresent(plan -> result.setHighestCostPlan(plan.getPlanId()));
    }

    private static boolean positiveOrZero(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) >= 0;
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
