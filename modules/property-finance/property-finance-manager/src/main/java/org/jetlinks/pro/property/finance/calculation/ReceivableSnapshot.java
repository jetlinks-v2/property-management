package org.jetlinks.pro.property.finance.calculation;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Builder
public final class ReceivableSnapshot {
    private final String receivableId;
    private final LocalDate dueDate;
    private final boolean lateFeeEnabled;
    private final String lateFeeCalculationType;
    private final String lateFeePeriodType;
    private final String lateFeeBaseType;
    private final BigDecimal lateFeeAmountOrRate;
    private final BigDecimal currentReceivable;
    private final BigDecimal accumulatedOverdue;
    private final boolean paid;
    private final boolean lateFeeLocked;
    private final LocalDate paidDate;
    private final int moneyScale;
}
