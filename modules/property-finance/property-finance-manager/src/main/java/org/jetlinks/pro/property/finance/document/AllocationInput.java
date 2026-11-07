package org.jetlinks.pro.property.finance.document;

import java.time.LocalDate;

/** 票据分配提交。结束号码由服务端计算，名称从公开用户/字典来源冻结。 */
public record AllocationInput(
        Long version,
        LocalDate buyDate,
        String recipientId,
        String billType,
        String billCode,
        String billSource,
        String quota,
        Integer bookCount,
        Integer numberCount,
        Long startNumber,
        Boolean enabled,
        Boolean recovered,
        String remark) {}
