package org.jetlinks.pro.property.base.archive;

import java.util.List;

/**
 * 与空间管理列表同口径的授权空间汇总，包含任意层级；面积为两位小数文本，未完善也计入总数及未设置房态。
 *
 * @since 2.20.0
 * @see org.jetlinks.pro.property.base.command.QueryPropertyHouseSummaryCommand
 */
public record PropertyHouseSummary(
        long houseCount,
        long incompleteCount,
        String buildingArea,
        String billingArea,
        List<StateCount> states) {
    /** 字典顺序稳定；历史禁用项有引用时仍保留，空 value 表示未设置。 */
    public record StateCount(String value, String text, long count) {}
}
