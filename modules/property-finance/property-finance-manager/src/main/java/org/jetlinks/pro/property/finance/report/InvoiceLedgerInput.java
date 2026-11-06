package org.jetlinks.pro.property.finance.report;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.Getter;
import lombok.Setter;

import java.util.*;

/** 逐张发票筛选；与收费来源覆盖是不同粒度。 */
@Getter
@Setter
public class InvoiceLedgerInput extends InvoiceReportInput {
    private String ticketColor;
    private String ticketStatus;
    private String buyerName;

    @Override
    public void validate() {
        super.validate();
        if (ticketColor != null && !Set.of("BLUE", "RED").contains(ticketColor)
                || ticketStatus != null
                        && !Set.of("SIMULATED_ISSUED", "SIMULATED_REVERSED", "SIMULATED_RED")
                                .contains(ticketStatus)
                || buyerName != null && buyerName.length() > 200)
            throw error("report_query_invalid", 400);
    }
}
