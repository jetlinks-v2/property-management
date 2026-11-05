package org.jetlinks.pro.property.finance.setting;

/** 自动任务分阶段执行；即时保存使用ALL，不能把生成任务误当每日全量重算。 */
public enum BillingTaskKind {
    GENERATE,
    ACCRUE,
    CLOSE,
    LATE_FEE,
    ALL;

    public boolean accrue() {
        return this == ALL || this == GENERATE || this == ACCRUE || this == CLOSE;
    }

    public boolean closePeriod() {
        return this == ALL || this == CLOSE;
    }

    public boolean lateFee() {
        return this == ALL || this == LATE_FEE;
    }
}
