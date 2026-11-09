package org.jetlinks.pro.property.finance.service;

import java.util.*;

/** 与报表导出共用字段文案，金额单位明确。 */
final class AnalysisLabels {
    private AnalysisLabels() {}

    private static final Map<String, String> ZH =
            Map.ofEntries(
                    Map.entry("priorYears", "收回往年费用（元）"),
                    Map.entry("priorMonths", "收回往月费用（元）"),
                    Map.entry("currentMonth", "收回本月费用（元）"),
                    Map.entry("future", "收回未来费用（元）"),
                    Map.entry("cash", "现金核销（元）"),
                    Map.entry("restored", "退款恢复欠费（元）"),
                    Map.entry("sourceNo", "关联业务单号"),
                    Map.entry("giftMonths", "赠送月数"),
                    Map.entry("overdueSpaceCount", "逾期空间数"),
                    Map.entry("earliestDate", "最早欠费日期"),
                    Map.entry("overdueDays", "逾期天数"),
                    Map.entry("applicant", "申请人"),
                    Map.entry("reviewer", "审批人"),
                    Map.entry("approvedAt", "审批时间"),
                    Map.entry("state", "状态"),
                    Map.entry("projectName", "项目"),
                    Map.entry("fullName", "空间路径"),
                    Map.entry("customerName", "账单客户"),
                    Map.entry("subjectName", "收费科目"),
                    Map.entry("billNo", "业务单号"),
                    Map.entry("receivableDate", "应收日期"),
                    Map.entry("dueDate", "到期日期"),
                    Map.entry("calcStartDate", "费用开始日期"),
                    Map.entry("calcEndDate", "费用结束日期"),
                    Map.entry("age", "欠费账龄"),
                    Map.entry("month", "月份"),
                    Map.entry("type", "资金类型"),
                    Map.entry("activityName", "活动名称"),
                    Map.entry("occurredAt", "业务时间"),
                    Map.entry("operation", "变动类型"),
                    Map.entry("reason", "原因"),
                    Map.entry("actor", "操作人"),
                    Map.entry("beforeBalance", "变动前余额（元）"),
                    Map.entry("amount", "本次变动（元）"),
                    Map.entry("afterBalance", "变动后余额（元）"),
                    Map.entry("spaceCount", "空间数"),
                    Map.entry("rowCount", "业务笔数"),
                    Map.entry("debt", "期末欠费（元）"),
                    Map.entry("overdue", "逾期欠费（元）"),
                    Map.entry("notDue", "未到期（元）"),
                    Map.entry("days30", "逾期1～30天（元）"),
                    Map.entry("days90", "逾期31～90天（元）"),
                    Map.entry("days180", "逾期91～180天（元）"),
                    Map.entry("days365", "逾期181～365天（元）"),
                    Map.entry("over365", "逾期365天以上（元）"),
                    Map.entry("undated", "未设到期日（元）"),
                    Map.entry("opening", "期初余额（元）"),
                    Map.entry("accrual", "本期净应收变化（元）"),
                    Map.entry("settled", "本期核销净变化（元）"),
                    Map.entry("income", "现金存入（元）"),
                    Map.entry("refund", "现金退还（元）"),
                    Map.entry("transferIn", "划入（元）"),
                    Map.entry("transferOut", "划出（元）"),
                    Map.entry("deduct", "扣款（元）"),
                    Map.entry("offset", "余额冲抵（元）"),
                    Map.entry("offsetReturn", "冲抵退回（元）"),
                    Map.entry("other", "其他变动（元）"),
                    Map.entry("movement", "余额净变化（元）"),
                    Map.entry("closing", "期末余额（元）"),
                    Map.entry("principal", "收款本金（元）"),
                    Map.entry("discount", "已落账优惠（元）"),
                    Map.entry("periods", "优惠账期数"),
                    Map.entry("before", "变动前应收（元）"),
                    Map.entry("after", "变动后应收（元）"),
                    Map.entry("adjustment", "应收调整（元）"),
                    Map.entry("remission", "费用减免变化（元）"),
                    Map.entry("lateRemission", "滞纳金减免变化（元）"));

    static String value(String key, String value) {
        if (!Set.of("type", "operation", "age", "state").contains(key)) return value;
        return switch (value) {
            case "ADVANCE" -> "预收款";
            case "DEPOSIT" -> "押金";
            case "TOPUP" -> "存入";
            case "REVOKE_TOPUP" -> "收款撤销";
            case "TRANSFER_IN" -> "划入";
            case "TRANSFER_OUT" -> "划出";
            case "REFUND" -> "退款";
            case "OFFSET" -> "余额冲抵";
            case "RETURN_OFFSET" -> "冲抵退回";
            case "RETURN_BALANCE" -> "退款恢复余额";
            case "DEDUCT" -> "扣款";
            case "ADJUST" -> "应收调整";
            case "SPLIT" -> "拆分";
            case "COLLECT" -> "收款核销";
            case "PREPAY_COLLECT" -> "活动预缴";
            case "REDUCE" -> "费用减免";
            case "REDUCE_LATE_FEE" -> "滞纳金减免";
            case "EFFECTIVE" -> "已生效";
            case "NOT_DUE" -> "未到期";
            case "DAYS_30" -> "逾期1～30天";
            case "DAYS_90" -> "逾期31～90天";
            case "DAYS_180" -> "逾期91～180天";
            case "DAYS_365" -> "逾期181～365天";
            case "OVER_365" -> "逾期365天以上";
            case "UNDATED" -> "未设到期日";
            default -> value;
        };
    }

    static String label(String key) {
        return ZH.getOrDefault(key, key);
    }
}
