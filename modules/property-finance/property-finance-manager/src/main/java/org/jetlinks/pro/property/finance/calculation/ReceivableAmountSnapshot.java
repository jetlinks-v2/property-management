package org.jetlinks.pro.property.finance.calculation;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class ReceivableAmountSnapshot {
    private final BigDecimal contractReceivableAmount;
    private final BigDecimal accruedReceivableAmount;
    private final LocalDate accruedAsOfDate;

    public ReceivableAmountSnapshot(BigDecimal contractReceivableAmount,
                                    BigDecimal accruedReceivableAmount,
                                    LocalDate accruedAsOfDate) {
        this.contractReceivableAmount = contractReceivableAmount;
        this.accruedReceivableAmount = accruedReceivableAmount;
        this.accruedAsOfDate = accruedAsOfDate;
    }

    public BigDecimal getContractReceivableAmount() {
        return contractReceivableAmount;
    }

    public BigDecimal getAccruedReceivableAmount() {
        return accruedReceivableAmount;
    }

    public LocalDate getAccruedAsOfDate() {
        return accruedAsOfDate;
    }
}
