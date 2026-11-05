package org.jetlinks.pro.property.finance.report;

import org.hswebframework.web.i18n.LocaleUtils;
import org.springframework.stereotype.Component;

import java.util.*;

/** 只渲染已经授权的报表快照；Excel文本防公式执行，PDF不允许读取外部资源。 */
@Component
public class ReceivableReportFile {
    private static final List<String> MONEY =
            List.of(
                    "receivableAmount",
                    "remissionAmount",
                    "netAmount",
                    "receivedAmount",
                    "arrearsAmount",
                    "collectionRate");
    private static final Map<String, String> ZH =
            Map.ofEntries(
                    Map.entry("title", "应收统计"),
                    Map.entry("scope", "仅统计已审核、未删除且未拆分的账单；金额以已保存的计算截至日为准。"),
                    Map.entry("projectName", "项目"),
                    Map.entry("subjectName", "收费科目"),
                    Map.entry("fullName", "房屋"),
                    Map.entry("customerName", "收费客户"),
                    Map.entry("id", "账单编号"),
                    Map.entry("calcStartDate", "计费开始"),
                    Map.entry("calcEndDate", "计费结束"),
                    Map.entry("dueDate", "到期日"),
                    Map.entry("calculatedAsOfDate", "计算截至日"),
                    Map.entry("billCount", "账单数"),
                    Map.entry("receivableAmount", "减免前应收（元）"),
                    Map.entry("remissionAmount", "减免（元）"),
                    Map.entry("netAmount", "净应收（元）"),
                    Map.entry("receivedAmount", "已收（元）"),
                    Map.entry("arrearsAmount", "未收（元）"),
                    Map.entry("collectionRate", "收缴率（%）"),
                    Map.entry("summary", "全筛选范围合计"));
    private static final Map<String, String> EN =
            Map.ofEntries(
                    Map.entry("title", "Receivable report"),
                    Map.entry(
                            "scope",
                            "Approved, active, unsplit bills only. Amounts use the saved"
                                    + " calculation date."),
                    Map.entry("projectName", "Project"),
                    Map.entry("subjectName", "Charge subject"),
                    Map.entry("fullName", "Space"),
                    Map.entry("customerName", "Customer"),
                    Map.entry("id", "Bill ID"),
                    Map.entry("calcStartDate", "Charge start"),
                    Map.entry("calcEndDate", "Charge end"),
                    Map.entry("dueDate", "Due date"),
                    Map.entry("calculatedAsOfDate", "Calculated as of"),
                    Map.entry("billCount", "Bills"),
                    Map.entry("receivableAmount", "Gross due (CNY)"),
                    Map.entry("remissionAmount", "Reduction (CNY)"),
                    Map.entry("netAmount", "Net due (CNY)"),
                    Map.entry("receivedAmount", "Received (CNY)"),
                    Map.entry("arrearsAmount", "Outstanding (CNY)"),
                    Map.entry("collectionRate", "Collection rate (%)"),
                    Map.entry("summary", "Filtered total"));

    public byte[] write(ReceivableReportResult result, String format, Locale locale) {
        var columns = columns(result.groupBy());
        var labels = new LinkedHashMap<String, String>();
        columns.forEach(c -> labels.put(c, label(c, locale)));
        return FinanceReportFile.write(
                new FinanceReportFile.Data(
                        label("title", locale),
                        label("scope", locale),
                        label("summary", locale),
                        columns,
                        labels,
                        Set.copyOf(MONEY),
                        result.page().getData().stream().map(ReceivableReportFile::values).toList(),
                        amounts(result.summary())),
                format);
    }

    private static List<String> columns(String group) {
        var cols = new ArrayList<String>();
        if ("DETAIL".equals(group))
            cols.addAll(
                    List.of(
                            "projectName",
                            "fullName",
                            "customerName",
                            "subjectName",
                            "id",
                            "calcStartDate",
                            "calcEndDate",
                            "dueDate",
                            "calculatedAsOfDate"));
        else
            cols.addAll(
                    List.of("PROJECT".equals(group) ? "projectName" : "subjectName", "billCount"));
        cols.addAll(MONEY);
        return cols;
    }

    private static Map<String, String> values(ReceivableReportRow row) {
        var values = new HashMap<>(amounts(row.amounts()));
        values.put("projectName", Objects.toString(row.projectName(), ""));
        values.put("subjectName", Objects.toString(row.subjectName(), ""));
        values.put("fullName", Objects.toString(row.fullName(), ""));
        values.put("customerName", Objects.toString(row.customerName(), ""));
        values.put("id", Objects.toString(row.id(), ""));
        values.put("calcStartDate", Objects.toString(row.calcStartDate(), ""));
        values.put("calcEndDate", Objects.toString(row.calcEndDate(), ""));
        values.put("dueDate", Objects.toString(row.dueDate(), ""));
        values.put("calculatedAsOfDate", Objects.toString(row.calculatedAsOfDate(), ""));
        values.put("billCount", Long.toString(row.billCount()));
        return values;
    }

    private static Map<String, String> amounts(ReceivableReportAmounts a) {
        return Map.of(
                "receivableAmount",
                a.receivableAmount(),
                "remissionAmount",
                a.remissionAmount(),
                "netAmount",
                a.netAmount(),
                "receivedAmount",
                a.receivedAmount(),
                "arrearsAmount",
                a.arrearsAmount(),
                "collectionRate",
                a.collectionRate());
    }

    private static String label(String key, Locale locale) {
        return LocaleUtils.resolveMessage(
                "message.finance.report.receivable." + key,
                locale,
                ("zh".equals(locale.getLanguage()) ? ZH : EN).get(key));
    }
}
