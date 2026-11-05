package org.jetlinks.pro.property.finance.calculation;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class ReceivableTaxCalculator {
    private static final int MONEY_SCALE = 6;

    public TaxResult calculate(BigDecimal actualReceivable, BigDecimal taxRate,
                               String requestedCollectionMethod, BigDecimal requestedDifference) {
        if (actualReceivable == null || actualReceivable.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("实际应收不能小于0");
        }
        BigDecimal rate = taxRate == null ? BigDecimal.ZERO : taxRate;
        if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("税率必须位于0到1之间");
        }
        String collectionMethod = normalizeCollectionMethod(requestedCollectionMethod);
        BigDecimal difference = requestedDifference == null
                ? BigDecimal.ZERO : requestedDifference;
        if (difference.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("差额不能小于0");
        }
        BigDecimal effectiveDifference = difference.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if ("FULL".equals(collectionMethod) && effectiveDifference.signum() != 0) {
            throw new IllegalArgumentException("全额征收不允许填写差额");
        }
        if ("DIFFERENTIAL".equals(collectionMethod)
                && effectiveDifference.compareTo(actualReceivable) >= 0) {
            throw new IllegalArgumentException("差额必须小于实际应收");
        }
        BigDecimal baseOutputTax = outputTax(actualReceivable, rate);
        BigDecimal outputTax = outputTax(actualReceivable.subtract(effectiveDifference), rate);
        BigDecimal income = actualReceivable.subtract(outputTax)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return new TaxResult(rate, collectionMethod, baseOutputTax,
                effectiveDifference, outputTax, income);
    }

    public TaxResult calculate(BigDecimal actualReceivable, BigDecimal taxRate,
                               BigDecimal legacyDifferenceAmount) {
        String method = legacyDifferenceAmount != null && legacyDifferenceAmount.signum() > 0
                ? "DIFFERENTIAL" : "FULL";
        return calculate(actualReceivable, taxRate, method, legacyDifferenceAmount);
    }

    private String normalizeCollectionMethod(String value) {
        String method = value == null || value.trim().isEmpty()
                ? "FULL" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"FULL".equals(method) && !"DIFFERENTIAL".equals(method)) {
            throw new IllegalArgumentException("征收方式仅支持FULL或DIFFERENTIAL");
        }
        return method;
    }

    private BigDecimal outputTax(BigDecimal taxIncludedAmount, BigDecimal rate) {
        if (rate.signum() == 0) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        return taxIncludedAmount.divide(BigDecimal.ONE.add(rate), 12, RoundingMode.HALF_UP)
                .multiply(rate).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public static final class TaxResult {
        private final BigDecimal taxRate;
        private final String taxationCollectionMethod;
        private final BigDecimal baseOutputTaxAmount;
        private final BigDecimal differenceAmount;
        private final BigDecimal outputTaxAmount;
        private final BigDecimal incomeExcludingTax;

        private TaxResult(BigDecimal taxRate, String taxationCollectionMethod,
                          BigDecimal baseOutputTaxAmount, BigDecimal differenceAmount,
                          BigDecimal outputTaxAmount,
                          BigDecimal incomeExcludingTax) {
            this.taxRate = taxRate;
            this.taxationCollectionMethod = taxationCollectionMethod;
            this.baseOutputTaxAmount = baseOutputTaxAmount;
            this.differenceAmount = differenceAmount;
            this.outputTaxAmount = outputTaxAmount;
            this.incomeExcludingTax = incomeExcludingTax;
        }

        public BigDecimal getTaxRate() {
            return taxRate;
        }

        public BigDecimal getBaseOutputTaxAmount() {
            return baseOutputTaxAmount;
        }

        public String getTaxationCollectionMethod() {
            return taxationCollectionMethod;
        }

        public BigDecimal getDifferenceAmount() {
            return differenceAmount;
        }

        public BigDecimal getInvoiceDeductionAmount() {
            return differenceAmount;
        }

        public BigDecimal getOutputTaxAmount() {
            return outputTaxAmount;
        }

        public BigDecimal getIncomeExcludingTax() {
            return incomeExcludingTax;
        }
    }
}
