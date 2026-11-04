package org.jetlinks.pro.property.finance.payment;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/** 资金业务动作，未开放任意余额覆盖；来源与目标必须由服务器解析。 */
@Getter
@Setter
public class FundsInput {
    private String action,
            type,
            accountId,
            spaceId,
            relationshipId,
            standardId,
            companyId,
            method,
            recordedId,
            occurredAt,
            payerName,
            reason,
            attachmentFileId,
            attachmentName;
    private Long version;
    private BigDecimal amount, quantity;
    private String startDate,
            endDate,
            targetSpaceId,
            targetRelationshipId,
            targetStandardId,
            targetType;
    private List<CashierInput.Item> items;
}
