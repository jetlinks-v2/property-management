package org.jetlinks.pro.property.finance.receivable;

import static org.junit.jupiter.api.Assertions.*;

import org.jetlinks.pro.property.finance.entity.ReceivableEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

class ReceivablePolicyTest {
    private ReceivableEntity bill() {
        var row = new ReceivableEntity();
        row.setDeleted(false);
        row.setPeriodStatus("OPEN");
        row.setArrears(new BigDecimal("100"));
        row.setPaidAmount(BigDecimal.ZERO);
        return row;
    }

    @Test
    void frozenAndClosedPeriodsHaveDifferentOperations() {
        var row = bill();
        assertTrue(
                ReceivablePolicy.allowed(row)
                        .containsAll(Set.of("FREEZE", "DELETE", "ADJUST", "SPLIT")));
        row.setFrozen(true);
        assertEquals(Set.of("DETAIL", "LOG", "UNFREEZE"), ReceivablePolicy.allowed(row));
        row.setFrozen(false);
        row.setPeriodStatus("CLOSED");
        assertEquals(Set.of("DETAIL", "LOG", "ADJUST", "SPLIT"), ReceivablePolicy.allowed(row));
        row.setChargeLocked(true);
        assertEquals(Set.of("DETAIL", "LOG", "SPLIT"), ReceivablePolicy.allowed(row));
    }

    @Test
    void financialFactsAndLocksProtectChanges() {
        for (String fact :
                new String[] {
                    "paymentLocked",
                    "bankCollectionLocked",
                    "discountLocked",
                    "financialClosed",
                    "split",
                    "deleted"
                }) {
            var row = bill();
            new org.springframework.beans.BeanWrapperImpl(row).setPropertyValue(fact, true);
            assertEquals(Set.of("DETAIL", "LOG"), ReceivablePolicy.allowed(row), fact);
        }
        for (String fact : new String[] {"invoiceOccupiedAmount", "paymentOccupiedAmount"}) {
            var row = bill();
            new org.springframework.beans.BeanWrapperImpl(row)
                    .setPropertyValue(fact, BigDecimal.ONE);
            assertEquals(Set.of("DETAIL", "LOG"), ReceivablePolicy.allowed(row), fact);
        }
    }

    @Test
    void partialPaymentMayFreezeButCannotDeleteOrAdjust() {
        var row = bill();
        row.setPaidAmount(BigDecimal.ONE);
        assertEquals(Set.of("DETAIL", "LOG", "FREEZE"), ReceivablePolicy.allowed(row));
        row.setArrears(BigDecimal.ZERO);
        assertEquals(Set.of("DETAIL", "LOG"), ReceivablePolicy.allowed(row));
    }
}
