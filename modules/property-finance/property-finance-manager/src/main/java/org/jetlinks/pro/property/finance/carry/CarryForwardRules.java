package org.jetlinks.pro.property.finance.carry;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import org.jetlinks.pro.property.finance.entity.FundAccountEntity;
import org.jetlinks.pro.property.finance.entity.ReceivableEntity;
import org.jetlinks.pro.property.finance.setting.SettingRules;
import org.springframework.scheduling.support.CronExpression;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 自动结转的纯规则；金额来自当日重算后的账单，持久执行器负责锁、权限及一次减免约束。 */
public final class CarryForwardRules {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final DateTimeFormatter SLOT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private CarryForwardRules() {}

    public static void validate(CarryForwardPlanInput p) {
        if (p == null
                || p.getName() == null
                || p.getName().isBlank()
                || p.getName().length() > 100
                || p.getSubjectIds() == null
                || p.getSubjectIds().size() != 1
                || p.getEnabled() == null
                || p.getIncludeLateFee() == null) throw error("carry_plan_invalid", 400);
        var ids = new HashSet<String>();
        for (var id : p.getSubjectIds())
            if (id == null || id.isBlank() || id.length() > 64 || !ids.add(id))
                throw error("carry_plan_invalid", 400);
        if (p.getStandardIds() == null || p.getStandardIds().size() != 1)
            throw error("carry_plan_invalid", 400);
        ids.clear();
        for (var id : p.getStandardIds())
            if (id == null || id.isBlank() || id.length() > 64 || !ids.add(id))
                throw error("carry_plan_invalid", 400);
        try {
            if (LocalDate.parse(p.getPeriodEnd()).isBefore(LocalDate.parse(p.getPeriodStart())))
                throw error("carry_plan_invalid", 400);
            ZoneId.of(p.getZone());
            schedule(p);
        } catch (DateTimeException | IllegalArgumentException | NullPointerException failure) {
            throw error("carry_plan_invalid", 400);
        }
        if (!Set.of("NONE", "DISCOUNT", "FIXED")
                .contains(Objects.toString(p.getRemissionMode(), "")))
            throw error("carry_plan_invalid", 400);
        var value = p.getRemissionValue();
        if ("NONE".equals(p.getRemissionMode())) {
            if (value != null && value.signum() != 0) throw error("carry_plan_invalid", 400);
        } else {
            money(value);
            if (value.signum() <= 0
                    || "DISCOUNT".equals(p.getRemissionMode())
                            && value.compareTo(BigDecimal.TEN) > 0)
                throw error("carry_plan_invalid", 400);
        }
    }

    private static CronExpression schedule(CarryForwardPlanInput p) {
        var at = LocalTime.parse(p.getExecuteAt());
        if (at.getNano() != 0) throw error("carry_plan_invalid", 400);
        String calendar;
        var day = Objects.toString(p.getExecuteDay(), "");
        switch (Objects.toString(p.getFrequency(), "")) {
            case "DAILY" -> {
                if (!day.isEmpty()) throw error("carry_plan_invalid", 400);
                calendar = "* * *";
            }
            case "WEEKLY" -> {
                if (!day.matches("[1-7]")) throw error("carry_plan_invalid", 400);
                calendar = "* * " + day;
            }
            case "MONTHLY" -> {
                if (!day.matches("[1-9]|[12][0-9]|3[01]")) throw error("carry_plan_invalid", 400);
                calendar = day + " * *";
            }
            case "YEARLY" -> {
                var date = MonthDay.parse("--" + day);
                calendar = date.getDayOfMonth() + " " + date.getMonthValue() + " *";
            }
            default -> throw error("carry_plan_invalid", 400);
        }
        return CronExpression.parse(
                at.getSecond() + " " + at.getMinute() + " " + at.getHour() + " " + calendar);
    }

    /** 仅返回今天已到达的计划槽位；31日和闰日遵循真实日历，不改为短月月末。 */
    public static Optional<String> due(CarryForwardPlanInput p, Instant now) {
        validate(p);
        if (!p.getEnabled()) return Optional.empty();
        var local = now.atZone(ZoneId.of(p.getZone())).toLocalDateTime();
        var slot = schedule(p).next(local.toLocalDate().atStartOfDay().minusSeconds(1));
        return slot != null
                        && slot.toLocalDate().equals(local.toLocalDate())
                        && !slot.isAfter(local)
                ? Optional.of(SLOT.format(slot))
                : Optional.empty();
    }

    /** 旧逻辑按每笔应收打折或固定减免。既往实收按先本金后滞纳金解释，关掉滞纳金时保留其欠费。 一次减免记录必须与结转原子写入；余额为零且不能全额减免时，不先给一笔孤立减免。 */
    public static Quote quote(
            CarryForwardPlanInput p,
            BigDecimal arrears,
            BigDecimal lateFee,
            BigDecimal balance,
            boolean alreadyRemitted) {
        validate(p);
        arrears = money(arrears);
        lateFee = money(lateFee).min(arrears);
        balance = money(balance);
        var principal = arrears.subtract(lateFee);
        var eligible = p.getIncludeLateFee() ? arrears : principal;
        var remission = ZERO;
        if (!alreadyRemitted && !"NONE".equals(p.getRemissionMode())) {
            remission =
                    "FIXED".equals(p.getRemissionMode())
                            ? p.getRemissionValue().min(eligible)
                            : eligible.subtract(
                                    eligible.multiply(p.getRemissionValue())
                                            .divide(BigDecimal.TEN, 2, RoundingMode.HALF_UP));
        }
        remission = remission.setScale(2);
        var payable = eligible.subtract(remission);
        if (balance.signum() == 0 && payable.signum() > 0) remission = ZERO;
        var amount = balance.min(eligible.subtract(remission));
        var feeReduction = remission.min(principal);
        return new Quote(
                amount,
                feeReduction,
                remission.subtract(feeReduction),
                arrears.subtract(remission).subtract(amount));
    }

    /** 预收款与账单须属于同项目、房屋、科目、标准、公司及收费对象，禁止跨标准抵扣。 */
    public static boolean matches(
            String project,
            CarryForwardPlanInput p,
            ReceivableEntity bill,
            FundAccountEntity account) {
        validate(p);
        if (!Objects.equals(project, bill.getProjectId())
                || !Objects.equals(project, account.getProjectId())
                || !"ADVANCE".equals(account.getType())
                || !Objects.equals(bill.getSpaceId(), account.getSpaceId())
                || !Objects.equals(bill.getSubjectId(), account.getSubjectId())
                || !Objects.equals(bill.getStandardId(), account.getStandardId())
                || !p.getSubjectIds().contains(bill.getSubjectId())
                || !includesStandard(p, bill.getStandardId())
                || Boolean.TRUE.equals(bill.getDeleted())
                || Boolean.TRUE.equals(account.getDeleted())
                || bill.getCalcStartDate().compareTo(p.getPeriodStart()) < 0
                || bill.getCalcEndDate().compareTo(p.getPeriodEnd()) > 0
                || !SettingRules.selectedCustomer(bill, account.getCustomerId())) return false;
        var company = SettingRules.standard(bill.getSnapshotJson()).getCompanyId();
        return company == null || company.equals(account.getCompanyId());
    }

    public static boolean includesStandard(CarryForwardPlanInput p, String standardId) {
        return p.getStandardIds() != null
                && p.getStandardIds().size() == 1
                && p.getStandardIds().contains(standardId);
    }

    private static BigDecimal money(BigDecimal value) {
        if (value == null
                || value.signum() < 0
                || value.scale() > 2
                || value.precision() - value.scale() > 18) throw error("carry_amount_invalid", 400);
        return value.setScale(2);
    }

    public record Quote(
            BigDecimal amount,
            BigDecimal feeReduction,
            BigDecimal lateFeeReduction,
            BigDecimal remaining) {}
}
