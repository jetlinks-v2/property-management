package org.jetlinks.pro.property.finance.subject;

import java.util.List;

public record ArrearsInput(Long version, String name, String remark, List<Rule> rules) {
    public record Rule(String id, Integer overdueDays, String levelCode) {}
}
