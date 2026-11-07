package org.jetlinks.pro.property.finance.payment;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Excel与待审编辑共享输入。显示名称只能精确唯一匹配，不接受任意外部财务快照。 */
@Getter
@Setter
public class CollectionImportInput {
    private String externalNo,
            spaceId,
            relationshipId,
            subjectId,
            standardId,
            receivableId,
            companyId,
            method,
            recordedId,
            paidAt,
            payerName,
            startDate,
            endDate,
            accountPeriod,
            remark,
            fullName,
            customerName,
            subjectName,
            standardName,
            companyName,
            methodText,
            bankName,
            reductionReason;
    private BigDecimal amount, quantity, reductionAmount;
}
