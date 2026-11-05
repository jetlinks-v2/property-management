package org.jetlinks.pro.property.finance.payment;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.alibaba.fastjson.JSON;

import java.math.BigDecimal;
import java.time.*;

/** 临时收款按柜台确认的金额入账，期间和标准只作为冻结业务依据，不生成应收。 */
public final class TemporaryRules {
    private TemporaryRules() {}

    public static void validate(TemporaryInput input, LocalDate today) {
        if (input == null) throw error("temporary_invalid", 400);
        PaymentRules.validateFunding(input.fundingInput(), today);
        if (blank(input.getSpaceId())
                || blank(input.getRelationshipId())
                || blank(input.getStandardId())
                || input.getSpaceId().length() > 64
                || input.getRelationshipId().length() > 64
                || input.getStandardId().length() > 64
                || input.getSubjectId() != null && input.getSubjectId().length() > 64)
            throw error("temporary_invalid", 400);
        try {
            var start = LocalDate.parse(input.getStartDate());
            var end = LocalDate.parse(input.getEndDate());
            YearMonth.parse(input.getAccountPeriod());
            if (start.isAfter(end)) throw error("temporary_invalid", 400);
        } catch (java.time.format.DateTimeParseException | NullPointerException invalid) {
            throw error("temporary_invalid", 400);
        }
        if (input.getQuantity() != null
                && (input.getQuantity().signum() <= 0
                        || input.getQuantity().scale() > 4
                        || input.getQuantity().precision() > 12))
            throw error("temporary_invalid", 400);
        var total =
                input.getTenders().stream()
                        .map(tender -> TemporaryRules.money(tender.amount()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (TemporaryRules.money(input.getAmount()).compareTo(total) != 0)
            throw error("cashier_total_mismatch", 400);
    }

    public static BigDecimal money(BigDecimal value) {
        if (value == null
                || value.signum() <= 0
                || value.scale() > 2
                || value.precision() - value.scale() > 18) throw error("temporary_invalid", 400);
        return value.setScale(2);
    }

    public static String fingerprint(TemporaryInput input) {
        return PaymentRules.digest(canonical(input));
    }

    public static String canonical(TemporaryInput input) {
        return JSON.toJSONString(input);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
