package org.jetlinks.pro.property.finance.receivable;

import java.time.LocalDate;

/** 日期分界属于第一段；两段收费客户均以房屋关系身份确认，金额只由后端生成。 */
public record SplitInput(
        Long version,
        LocalDate splitDate,
        Integer scale,
        String firstRelationshipId,
        String secondRelationshipId,
        String reason) {}
