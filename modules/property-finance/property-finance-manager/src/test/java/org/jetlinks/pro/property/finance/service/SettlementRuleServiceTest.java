package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.finance.tax.RecordedInfoInput;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

class SettlementRuleServiceTest {
    private final SettlementRuleService service = new SettlementRuleService();

    @Test
    void wholeAmountUsesInclusiveLowerExclusiveUpperThenBoundsAndHalfUpCents() {
        var input = percentage();
        assertEquals(new BigDecimal("0.00"), service.preview(input, new BigDecimal("0")));
        assertEquals(new BigDecimal("1.00"), service.preview(input, new BigDecimal("99.99")));
        assertEquals(new BigDecimal("2.00"), service.preview(input, new BigDecimal("100.00")));
        assertEquals(new BigDecimal("2.01"), service.preview(input, new BigDecimal("100.25")));
        input.setMinimumFee(new BigDecimal("3.00"));
        input.setMaximumFee(new BigDecimal("5.00"));
        assertEquals(new BigDecimal("3.00"), service.preview(input, new BigDecimal("10.00")));
        assertEquals(new BigDecimal("5.00"), service.preview(input, new BigDecimal("1000.00")));
    }

    @Test
    void fixedAmountAndMissingConfigurationAreDifferentBusinessFacts() {
        var input = new RecordedInfoInput();
        assertThrows(BusinessException.class, () -> service.preview(input, BigDecimal.ONE));
        input.setServiceChargeType(2);
        input.setFixedAmount(new BigDecimal("1.25"));
        assertEquals(new BigDecimal("1.25"), service.preview(input, new BigDecimal("0.00")));
        assertEquals(new BigDecimal("1.25"), service.preview(input, new BigDecimal("999.99")));
        input.setFixedAmount(new BigDecimal("-0.01"));
        assertThrows(BusinessException.class, () -> service.validate(input));
    }

    @Test
    void overlappingGapsFiniteFinalTierAndExcessPrecisionCannotBecomeStoredRules() {
        var input = percentage();
        for (var invalid :
                List.of(
                        List.of(tier("0", "100", "1"), tier("99", null, "2")),
                        List.of(tier("0", "100", "1"), tier("101", null, "2")),
                        List.of(tier("0", "100", "1")),
                        List.of(tier("1", null, "1")),
                        List.of(tier("0", null, "100.000001")),
                        List.of(tier("0", null, "0.0000001")))) {
            input.setTiers(invalid);
            assertThrows(BusinessException.class, () -> service.validate(input));
        }
        assertThrows(
                BusinessException.class,
                () -> service.preview(percentage(), new BigDecimal("1.001")));
    }

    static RecordedInfoInput percentage() {
        var input = new RecordedInfoInput();
        input.setServiceChargeType(1);
        input.setTiers(List.of(tier("0", "100", "1"), tier("100", null, "2")));
        return input;
    }

    static RecordedInfoInput.Tier tier(String min, String max, String rate) {
        return new RecordedInfoInput.Tier(
                new BigDecimal(min),
                max == null ? null : new BigDecimal(max),
                new BigDecimal(rate));
    }
}
