package org.jetlinks.pro.property.finance.report;

import org.hswebframework.web.i18n.LocaleUtils;
import org.springframework.stereotype.Component;

import java.util.*;

/** 现金报表字段与名称快照；共用有界、中文、文本安全的Excel/PDF排版。 */
@Component
public class ReceivedReportFile {
    private static final List<String> MONEY =
            List.of(
                    "incomeAmount",
                    "refundAmount",
                    "netAmount",
                    "currentPeriodAmount",
                    "otherPeriodAmount",
                    "unhandedAmount",
                    "pendingAmount",
                    "approvedAmount", "olderYearAmount","olderMonthAmount","thisMonthAmount","futureAmount","unassignedAmount");
    private static final Map<String, String> ZH =
            Map.ofEntries(
                    Map.entry("olderYearAmount","收回往年费用（元）"),Map.entry("olderMonthAmount","收回往月费用（元）"),Map.entry("thisMonthAmount","收回当月费用（元）"),Map.entry("futureAmount","预收未来费用（元）"),Map.entry("unassignedAmount","未指定费用期间（元）"),Map.entry("title", "实收统计"),
                    Map.entry("handoverState", "交账状态"),
                    Map.entry("UNHANDED", "已收未交账"),
                    Map.entry("PENDING", "已收未入账"),
                    Map.entry("APPROVED", "已入账"),
                    Map.entry(
                            "scope",
                            "按实际现金收支统计，退款按退款日列负额；余额冲抵和内部转存不重复计现。其他期间包含跨期及无费用期间的流水；无日期条件时不拆分期间。"),
                    Map.entry("summary", "全筛选范围合计"),
                    Map.entry("projectName", "项目"),
                    Map.entry("fullName", "房屋"),
                    Map.entry("customerName", "客户"),
                    Map.entry("subjectName", "收费科目"),
                    Map.entry("sourceId", "收款/退款ID"),
                    Map.entry("sourceNo", "收款/退款编号"),
                    Map.entry("receivableId", "关联应收单号"),
                    Map.entry("receivedAt", "收支时间"),
                    Map.entry("calcStartDate", "费用开始"),
                    Map.entry("calcEndDate", "费用结束"),
                    Map.entry("methodText", "收款方式"),
                    Map.entry("sourceType", "收款类型"),
                    Map.entry("collectorName", "收款员"),
                    Map.entry("remark", "备注"),
                    Map.entry("unhandedAmount", "已收未交账（元）"),
                    Map.entry("pendingAmount", "已收未入账（元）"),
                    Map.entry("approvedAmount", "已入账（元）"),
                    Map.entry("rowCount", "流水数"),
                    Map.entry("incomeAmount", "收入（元）"),
                    Map.entry("refundAmount", "退款（元）"),
                    Map.entry("netAmount", "实收总额（元）"),
                    Map.entry("currentPeriodAmount", "当期实收（元）"),
                    Map.entry("otherPeriodAmount", "其他期间实收（元）"),
                    Map.entry("RECEIVABLE", "应收收款"),
                    Map.entry("TEMPORARY", "临时收款"),
                    Map.entry("REFUND", "收款退款"),
                    Map.entry("ADVANCE", "预收款"),
                    Map.entry("DEPOSIT", "押金"),
                    Map.entry("TEMPORARY_REFUND", "临时退款"),
                    Map.entry("ADVANCE_REFUND", "预收退还"),
                    Map.entry("DEPOSIT_REFUND", "押金退还"));
    private static final Map<String, String> EN =
            Map.ofEntries(
                    Map.entry("olderYearAmount","Prior years (CNY)"),Map.entry("olderMonthAmount","Prior months (CNY)"),Map.entry("thisMonthAmount","Current month (CNY)"),Map.entry("futureAmount","Future fees (CNY)"),Map.entry("unassignedAmount","Unspecified billing period (CNY)"),Map.entry("title", "Received report"),
                    Map.entry("handoverState", "Handover status"),
                    Map.entry("UNHANDED", "Received, not handed over"),
                    Map.entry("PENDING", "Received, not posted"),
                    Map.entry("APPROVED", "Posted"),
                    Map.entry(
                            "scope",
                            "Cash movements only; excludes balance offsets and internal transfers."
                                + " Other periods include missing billing dates. No period split"
                                + " without date filters."),
                    Map.entry("summary", "Filtered total"),
                    Map.entry("projectName", "Project"),
                    Map.entry("fullName", "Space"),
                    Map.entry("customerName", "Customer"),
                    Map.entry("subjectName", "Charge subject"),
                    Map.entry("sourceId", "Payment/refund ID"),
                    Map.entry("sourceNo", "Payment/refund reference"),
                    Map.entry("receivableId", "Receivable ID"),
                    Map.entry("receivedAt", "Transaction time"),
                    Map.entry("calcStartDate", "Billing start"),
                    Map.entry("calcEndDate", "Billing end"),
                    Map.entry("methodText", "Method"),
                    Map.entry("sourceType", "Source type"),
                    Map.entry("collectorName", "Collector"),
                    Map.entry("remark", "Remark"),
                    Map.entry("unhandedAmount", "Not handed over (CNY)"),
                    Map.entry("pendingAmount", "Pending posting (CNY)"),
                    Map.entry("approvedAmount", "Posted (CNY)"),
                    Map.entry("rowCount", "Rows"),
                    Map.entry("incomeAmount", "Income (CNY)"),
                    Map.entry("refundAmount", "Refund (CNY)"),
                    Map.entry("netAmount", "Net received (CNY)"),
                    Map.entry("currentPeriodAmount", "Current period (CNY)"),
                    Map.entry("otherPeriodAmount", "Other periods (CNY)"),
                    Map.entry("RECEIVABLE", "Receivable"),
                    Map.entry("TEMPORARY", "Temporary payment"),
                    Map.entry("REFUND", "Payment refund"),
                    Map.entry("ADVANCE", "Advance"),
                    Map.entry("DEPOSIT", "Deposit"),
                    Map.entry("TEMPORARY_REFUND", "Temporary refund"),
                    Map.entry("ADVANCE_REFUND", "Advance refund"),
                    Map.entry("DEPOSIT_REFUND", "Deposit refund"));

    public byte[] write(ReceivedReportResult result, String format, Locale locale) {
        return FinanceReportFile.write(data(result, locale), format);
    }

    public FinanceReportFile.Data data(ReceivedReportResult result, Locale locale) {
        var columns = new ArrayList<String>();
        if ("DETAIL".equals(result.groupBy()))
            columns.addAll(
                    List.of(
                            "projectName",
                            "fullName",
                            "customerName",
                            "subjectName",
                            "sourceNo",
                            "receivableId",
                            "receivedAt",
                            "calcStartDate",
                            "calcEndDate",
                            "methodText",
                            "sourceType",
                            "handoverState",
                            "collectorName",
                            "remark"));
        else
            columns.addAll(
                    List.of(
                            switch (result.groupBy()) {
                                case "PROJECT" -> "projectName";
                                case "SUBJECT" -> "subjectName";
                                case "METHOD" -> "methodText";
                                case "DAY", "MONTH" -> "receivedAt";
            case "HANDOVER" -> "handoverState";
            case "COLLECTOR" -> "collectorName";
                                default -> "sourceType";
                            },
                            "rowCount"));
        columns.addAll(
                MONEY.stream()
                        .filter(
                                key ->
                                        result.periodEnabled()
                                                || !Set.of(
                                                                "currentPeriodAmount",
                                                                "otherPeriodAmount")
                                                        .contains(key))
                        .toList());
        var labels = new LinkedHashMap<String, String>();
        columns.forEach(c -> labels.put(c, label(c, locale)));
        return new FinanceReportFile.Data(
                label("title", locale),
                label("scope", locale),
                label("summary", locale),
                columns,
                labels,
                Set.copyOf(MONEY),
                result.page().getData().stream().map(row -> values(row, locale)).toList(),
                amounts(result.summary()));
    }

    private static Map<String, String> values(ReceivedReportRow r, Locale locale) {
        var v = new HashMap<>(amounts(r.amounts()));
        v.put("projectName", text(r.projectName()));
        v.put("fullName", text(r.fullName()));
        v.put("customerName", text(r.customerName()));
        v.put("subjectName", text(r.subjectName()));
        v.put("sourceId", text(r.sourceId()));
        v.put("sourceNo", text(r.sourceNo()));
        v.put("receivableId", text(r.receivableId()));
        v.put("receivedAt", text(r.receivedAt()).replace('T', ' '));
        v.put("calcStartDate", text(r.calcStartDate()));
        v.put("calcEndDate", text(r.calcEndDate()));
        v.put("methodText", text(r.methodText()));
        v.put("sourceType", r.sourceType() == null ? "" : label(r.sourceType(), locale));
        v.put("handoverState", r.handoverState() == null ? "" : label(r.handoverState(), locale));
        v.put("collectorName", text(r.collectorName()));
        v.put("remark", text(r.remark()));
        v.put("rowCount", Long.toString(r.rowCount()));
        return v;
    }

    private static Map<String, String> amounts(ReceivedReportAmounts a) {
        var v = new HashMap<String, String>();
        v.put("olderYearAmount",a.olderYearAmount());v.put("olderMonthAmount",a.olderMonthAmount());v.put("thisMonthAmount",a.thisMonthAmount());v.put("futureAmount",a.futureAmount());v.put("unassignedAmount",a.unassignedAmount());
        v.put("unhandedAmount", a.unhandedAmount());
        v.put("pendingAmount", a.pendingAmount());
        v.put("approvedAmount", a.approvedAmount());
        v.put("incomeAmount", a.incomeAmount());
        v.put("refundAmount", a.refundAmount());
        v.put("netAmount", a.netAmount());
        v.put("currentPeriodAmount", text(a.currentPeriodAmount()));
        v.put("otherPeriodAmount", text(a.otherPeriodAmount()));
        return v;
    }

    private static String text(String value) {
        return Objects.toString(value, "");
    }

    private static String label(String key, Locale locale) {
        return LocaleUtils.resolveMessage(
                "message.finance.report.received." + key,
                locale,
                ("zh".equals(locale.getLanguage()) ? ZH : EN).getOrDefault(key, key));
    }
}
