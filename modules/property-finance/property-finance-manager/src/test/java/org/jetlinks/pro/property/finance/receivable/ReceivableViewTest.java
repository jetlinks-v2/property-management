package org.jetlinks.pro.property.finance.receivable;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.fastjson.JSON;

import org.jetlinks.pro.property.finance.entity.ReceivableEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

class ReceivableViewTest {
    private ReceivableEntity bill() {
        var row = new ReceivableEntity();
        row.setDueDate("2024-02-29");
        row.setPeriodStatus("CLOSED");
        row.setPaidAmount(new BigDecimal("10.00"));
        row.setArrears(new BigDecimal("90.00"));
        row.setPaidDate("2024-03-01");
        row.setSnapshotJson(
                JSON.toJSONString(
                        Map.of(
                                "standard",
                                Map.of("price", "1.23", "delayRoundType", 1),
                                "values",
                                Map.of("BILLING_AREA", "106.24"))));
        return row;
    }

    @Test
    void partialPaymentDoesNotStopOverdueAtLastPaymentDate() {
        var view = ReceivableView.of(bill(), false, LocalDate.of(2024, 4, 2));
        assertEquals("PARTIAL", view.getPayStatus());
        assertEquals("PARTIAL", view.getCollectionStatus());
        assertEquals(33L, view.getOverdueDays());
        assertEquals(1L, view.getOverdueMonths());
        assertNull(view.getCollectionDays());
        assertEquals(new BigDecimal("1.23"), view.getPrice());
        assertEquals(new BigDecimal("106.24"), view.getUsageValue());
        assertNull(view.getSnapshot(), "列表不泄露完整快照");
        assertNull(view.getOutputTaxAmount(), "未开票不能把配置税率当作已出税额");
    }

    @Test
    void fullPaymentStopsAtPaidDateButRefundedUnpaidRowKeepsAgeing() {
        var row = bill();
        row.setArrears(BigDecimal.ZERO);
        var view = ReceivableView.of(row, false, LocalDate.of(2024, 4, 2));
        assertEquals("PAID", view.getPayStatus());
        assertEquals(1L, view.getCollectionDays());
        assertEquals(1L, view.getOverdueDays());
        row.setPaidAmount(BigDecimal.ZERO);
        row.setArrears(new BigDecimal("100.00"));
        view = ReceivableView.of(row, false, LocalDate.of(2024, 4, 2));
        assertEquals("UNPAID", view.getPayStatus());
        assertEquals("OVERDUE", view.getCollectionStatus());
        assertEquals(33L, view.getOverdueDays());
    }

    @Test
    void historicalImportDoesNotInventOriginalPriceOrUsage() {
        var row = bill();
        row.setSourceType("HISTORICAL_IMPORT");
        var view = ReceivableView.of(row, false, LocalDate.of(2024, 2, 28));
        assertNull(view.getPrice());
        assertNull(view.getUsageValue());
        assertEquals(0L, view.getOverdueDays());
        row.setPaidAmount(BigDecimal.ZERO);
        view = ReceivableView.of(row, false, LocalDate.of(2024, 2, 28));
        assertEquals("NOT_DUE", view.getCollectionStatus());
        view = ReceivableView.of(row, false, LocalDate.of(2024, 2, 29));
        assertEquals("PENDING", view.getCollectionStatus());
    }
}
