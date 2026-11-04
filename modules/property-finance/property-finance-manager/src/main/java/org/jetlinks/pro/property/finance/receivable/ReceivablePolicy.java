package org.jetlinks.pro.property.finance.receivable;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import org.jetlinks.pro.property.finance.entity.ReceivableEntity;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;

/** 沿旧应收操作策略，财务月结和票据占用额外保护；前后端共用此能力判定。 */
public final class ReceivablePolicy {
    private ReceivablePolicy() {}

    public static Set<String> allowed(ReceivableEntity row) {
        var result = new LinkedHashSet<>(Set.of("DETAIL", "LOG"));
        if (Boolean.TRUE.equals(row.getDeleted())
                || Boolean.TRUE.equals(row.getSplit())
                || Boolean.TRUE.equals(row.getFinancialClosed())
                || Boolean.TRUE.equals(row.getDiscountLocked())
                || Boolean.TRUE.equals(row.getBankCollectionLocked())
                || Boolean.TRUE.equals(row.getPaymentLocked())
                || positive(row.getPaymentOccupiedAmount())
                || positive(row.getInvoiceOccupiedAmount())
                || positive(row.getPaidAmount()) && !positive(row.getArrears())) return result;
        if (Boolean.TRUE.equals(row.getFrozen())) {
            result.add("UNFREEZE");
            return result;
        }
        boolean unpaid = !positive(row.getPaidAmount());
        boolean chargeLocked = Boolean.TRUE.equals(row.getChargeLocked());
        if ("CLOSED".equals(row.getPeriodStatus()) || chargeLocked) {
            if (unpaid && !chargeLocked) result.add("ADJUST");
            if (unpaid) result.add("SPLIT");
            return result;
        }
        result.add("FREEZE");
        if (unpaid) result.addAll(Set.of("ADJUST", "SPLIT", "DELETE"));
        return result;
    }

    public static void assertAllowed(ReceivableEntity row, String operation) {
        if (!allowed(row).contains(operation)) throw error("receivable_operation_denied", 409);
    }

    private static boolean positive(BigDecimal amount) {
        return amount != null && amount.signum() > 0;
    }
}
