package org.jetlinks.pro.property.finance.document;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.jetlinks.pro.property.finance.calculation.ReceivableTaxCalculator;
import org.springframework.stereotype.Component;

import java.math.*;
import java.time.*;
import java.util.*;

/** 独立发票与收据共用规范来源和额度；模拟通道不放宽任何账务或税额约束。 */
@Component
@RequiredArgsConstructor
public class DocumentRules {
    private final ReceivableTaxCalculator calculator;

    /** 关联ID必须由持久来源解析；客户端不能自行声明另一应收或支付主单。 */
    public CanonicalSource canonical(
            String type, String sourceId, String receivableId, String paymentId) {
        if (!id(sourceId) || type == null) throw error("document_source_invalid", 400);
        return switch (type.trim().toUpperCase(Locale.ROOT)) {
            case "RECEIVABLE" -> new CanonicalSource("RECEIVABLE", sourceId);
            case "RECEIVED", "TEMPORARY" -> {
                if (receivableId != null) {
                    if (!id(receivableId)) throw error("document_source_invalid", 400);
                    yield new CanonicalSource("RECEIVABLE", receivableId);
                }
                if (!id(paymentId)) throw error("document_source_invalid", 400);
                // 多支付方式的临时款归一到主单，不能按现金/银行卡行分别领取额度。
                yield new CanonicalSource("TEMP_PAYMENT", paymentId);
            }
            case "TEMP_PAYMENT" -> new CanonicalSource("TEMP_PAYMENT", sourceId);
            default -> throw error("document_source_invalid", 400);
        };
    }

    public void assertDistinct(List<CanonicalSource> sources) {
        if (sources == null || sources.isEmpty() || sources.stream().anyMatch(Objects::isNull))
            throw error("document_source_invalid", 400);
        if (sources.size() > 1000) throw error("document_batch_limit", 400);
        if (new HashSet<>(sources).size() != sources.size())
            throw error("document_source_duplicate", 409);
    }

    /** occupied包含所有有效发票及收据，不按入口、来源别名或票据类型各算一份额度。 */
    public BigDecimal available(BigDecimal allowed, BigDecimal occupied) {
        return money(allowed).subtract(money(occupied)).max(BigDecimal.ZERO).setScale(2);
    }

    public BigDecimal amount(String value, boolean allowZero) {
        if (value == null || value.length() > 24 || !value.matches("[0-9]+(?:\\.[0-9]{1,2})?"))
            throw error("document_amount_invalid", 400);
        var result = money(new BigDecimal(value));
        if (!allowZero && result.signum() == 0) throw error("document_amount_invalid", 400);
        return result;
    }

    private BigDecimal money(BigDecimal value) {
        if (value == null
                || value.signum() < 0
                || value.stripTrailingZeros().scale() > 2
                || value.precision() - value.scale() > 18)
            throw error("document_amount_invalid", 400);
        return value.setScale(2);
    }

    /** 未收款应收沿原先票后款路径不提前过期；已有收款从真实收款时间计算开票期限。 */
    public boolean withinTime(LocalDateTime paidAt, LocalDateTime now, String mode, Integer days) {
        if (now == null
                || !Set.of("NATURAL_YEAR", "CUSTOM_DAYS").contains(Objects.toString(mode, ""))
                || "CUSTOM_DAYS".equals(mode) && (days == null || days < 1 || days > 36500))
            throw error("document_rule_invalid", 400);
        if (paidAt == null) return true;
        if (paidAt.isAfter(now)) return false;
        if ("NATURAL_YEAR".equals(mode)) return paidAt.getYear() == now.getYear();
        try {
            return !paidAt.plusDays(days).isBefore(now);
        } catch (DateTimeException invalid) {
            throw error("document_rule_invalid", 400);
        }
    }

    /** 共用已迁移的6位销项税口径；文档金额落到分，净额由总额减税额以保持守恒。 */
    public TaxAmounts tax(String gross, String rate, String method, String difference) {
        var amount = amount(gross, false);
        if (rate == null || rate.length() > 32 || !rate.matches("[0-9]+(?:\\.[0-9]+)?"))
            throw error("document_tax_invalid", 400);
        var taxRate = new BigDecimal(rate);
        if (taxRate.compareTo(BigDecimal.ONE) > 0
                || taxRate.stripTrailingZeros().scale() > 6
                || !Set.of("FULL", "DIFFERENTIAL").contains(Objects.toString(method, ""))
                || "DIFFERENTIAL".equals(method) && difference == null)
            throw error("document_tax_invalid", 400);
        var deduction = amount(difference == null ? "0" : difference, true);
        final ReceivableTaxCalculator.TaxResult calculated;
        try {
            calculated = calculator.calculate(amount, taxRate, method, deduction);
        } catch (IllegalArgumentException invalid) {
            throw error("document_tax_invalid", 400);
        }
        var outputTax = calculated.getOutputTaxAmount();
        var documentTax = outputTax.setScale(2, RoundingMode.HALF_UP);
        return new TaxAmounts(
                amount,
                taxRate,
                method,
                deduction,
                outputTax,
                amount.subtract(documentTax),
                documentTax);
    }

    /** 红冲本身释放原占用；只有重开动作才受重开次数约束。null表示旧配置不设上限。 */
    public boolean canReopen(int count, Integer maximum) {
        if (count < 0 || maximum != null && maximum < 0) throw error("document_rule_invalid", 400);
        return maximum == null || count < maximum;
    }

    private static boolean id(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= 64
                && value.equals(value.trim());
    }

    public record CanonicalSource(String type, String id) {
        public CanonicalSource {
            if (!Set.of("RECEIVABLE", "TEMP_PAYMENT").contains(Objects.toString(type, ""))
                    || !DocumentRules.id(id)) throw error("document_source_invalid", 400);
        }
    }

    public record TaxAmounts(
            BigDecimal grossAmount,
            BigDecimal taxRate,
            String collectionMethod,
            BigDecimal differenceAmount,
            BigDecimal outputTax,
            BigDecimal netAmount,
            BigDecimal taxAmount) {}
}
