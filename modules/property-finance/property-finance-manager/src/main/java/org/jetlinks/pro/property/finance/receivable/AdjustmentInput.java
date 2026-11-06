package org.jetlinks.pro.property.finance.receivable;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.*;

/** 当前旧页面的单行FORMULA/DIRECT契约；移除审批和过时lines，金额用字符串保持精度。 */
@Getter
@Setter
public class AdjustmentInput {
    private Long version;
    private String adjustmentMode;
    private String directDirection;
    private String directAmount;
    private FormulaLine formulaLine;
    private String assessmentAmount;
    private Integer temporaryDispatchCount;
    private String temporaryDispatchUnitPrice;
    private LocalDate calculationAsOfDate;
    private String lateFeeReductionAmount;
    private String lateFeeReductionReason;
    private String feeReductionAmount;
    private String reason;
    private String remark;
    private List<String> attachmentFileIds = new ArrayList<>();
    private Map<String, String> attachmentNames = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class FormulaLine {
        private String subjectId;
        private String standardId;
        private LocalDate afterCalcStartDate;
        private LocalDate afterCalcEndDate;
        private Map<String, String> afterVariableValues = new LinkedHashMap<>();
    }
}
