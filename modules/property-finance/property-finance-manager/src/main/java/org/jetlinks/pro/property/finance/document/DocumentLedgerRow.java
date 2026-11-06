package org.jetlinks.pro.property.finance.document;

import lombok.Getter;
import lombok.Setter;

import org.jetlinks.pro.property.finance.entity.DocumentEntity;
import org.jetlinks.pro.property.finance.service.DocumentPreparationService;

import java.util.List;

/** 票据只读台账；业务名称取开具快照，实时收款字段单独计算，不重写票面。 */
@Getter
@Setter
public class DocumentLedgerRow extends DocumentEntity {
    private String timeLimitType;
    private Integer limitDays;
    private Integer maxReopenTimes;
    private String buyerTitleType;
    private String fullName;
    private String subjectName;
    private String period;
    private String sourceType;
    private String paymentStatus;
    private String paidAmount;
    private String issuePaidAmount;
    private String issuePaymentStatus;
    private String issuerName;
    private List<DocumentPreparationService.Line> sources;
}
