package org.jetlinks.pro.property.finance.report;

import org.hswebframework.web.i18n.LocaleUtils;
import org.springframework.stereotype.Component;

import java.util.*;

/** 来源覆盖报表使用与页面相同的授权快照，明确模拟票据及最新票据筛选口径。 */
@Component
public class InvoiceReportFile {
    private static final List<String> MONEY =
            List.of("receivableAmount", "receivedAmount", "invoicedAmount", "uninvoicedAmount");
    private static final Map<String, String> ZH =
            Map.ofEntries(
                    Map.entry("title", "开票统计"),
                    Map.entry(
                            "scope", "按规范来源统计当前开票覆盖，仅含模拟发票，不含收据。开票日期、号码和类型匹配来源最新蓝票；未开金额不是即时可开额度。"),
                    Map.entry("summary", "全筛选范围合计"),
                    Map.entry("projectName", "项目"),
                    Map.entry("fullName", "房屋"),
                    Map.entry("customerName", "客户"),
                    Map.entry("subjectName", "收费科目"),
                    Map.entry("id", "来源单号"),
                    Map.entry("sourceType", "来源类型"),
                    Map.entry("invoiceStatus", "开票状态"),
                    Map.entry("invoiceNo", "最新模拟发票号"),
                    Map.entry("invoiceType", "最新发票类型"),
                    Map.entry("invoiceDate", "最新开票时间"),
                    Map.entry("buyerName", "最新购方名称"),
                    Map.entry("rowCount", "来源数"),
                    Map.entry("receivableAmount", "来源基数（元）"),
                    Map.entry("receivedAmount", "已收金额（元）"),
                    Map.entry("invoicedAmount", "已开金额（元）"),
                    Map.entry("uninvoicedAmount", "未开金额（元）"),
                    Map.entry("UNISSUED", "未开票"),
                    Map.entry("PARTIAL", "部分开票"),
                    Map.entry("INVOICED", "已开票"),
                    Map.entry("REVERSED", "已红冲"),
                    Map.entry("RECEIVABLE", "应收款"),
                    Map.entry("TEMP_PAYMENT", "临时收款"),
                    Map.entry("FUND_TOPUP", "预收/押金原存入"),
                    Map.entry("1", "普通发票"),
                    Map.entry("2", "专用发票"));
    private static final Map<String, String> EN =
            Map.ofEntries(
                    Map.entry("title", "Invoice coverage"),
                    Map.entry(
                            "scope",
                            "Current canonical source coverage; simulated invoices only, excluding"
                                + " receipts. Invoice filters match the latest blue invoice."
                                + " Uninvoiced balance is not immediately issuable quota."),
                    Map.entry("summary", "Filtered total"),
                    Map.entry("projectName", "Project"),
                    Map.entry("fullName", "Space"),
                    Map.entry("customerName", "Customer"),
                    Map.entry("subjectName", "Charge subject"),
                    Map.entry("id", "Source ID"),
                    Map.entry("sourceType", "Source type"),
                    Map.entry("invoiceStatus", "Invoice status"),
                    Map.entry("invoiceNo", "Latest simulated invoice"),
                    Map.entry("invoiceType", "Latest invoice type"),
                    Map.entry("invoiceDate", "Latest issue time"),
                    Map.entry("buyerName", "Latest buyer"),
                    Map.entry("rowCount", "Sources"),
                    Map.entry("receivableAmount", "Source basis (CNY)"),
                    Map.entry("receivedAmount", "Received (CNY)"),
                    Map.entry("invoicedAmount", "Invoiced (CNY)"),
                    Map.entry("uninvoicedAmount", "Uninvoiced (CNY)"),
                    Map.entry("UNISSUED", "Unissued"),
                    Map.entry("PARTIAL", "Partially invoiced"),
                    Map.entry("INVOICED", "Invoiced"),
                    Map.entry("REVERSED", "Reversed"),
                    Map.entry("RECEIVABLE", "Receivable"),
                    Map.entry("TEMP_PAYMENT", "Temporary payment"),
                    Map.entry("FUND_TOPUP", "Original fund top-up"),
                    Map.entry("1", "Ordinary invoice"),
                    Map.entry("2", "Special invoice"));

    public byte[] write(InvoiceReportResult result, String format, Locale locale) {
        var columns = new ArrayList<String>();
        if ("DETAIL".equals(result.groupBy()))
            columns.addAll(
                    List.of(
                            "projectName",
                            "fullName",
                            "customerName",
                            "subjectName",
                            "id",
                            "sourceType",
                            "invoiceStatus",
                            "invoiceNo",
                            "invoiceType",
                            "invoiceDate",
                            "buyerName"));
        else
            columns.addAll(
                    List.of(
                            "PROJECT".equals(result.groupBy()) ? "projectName" : "subjectName",
                            "rowCount"));
        columns.addAll(MONEY);
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
                        result.page().getData().stream().map(r -> values(r, locale)).toList(),
                        amounts(result.summary())),
                format);
    }

    private static Map<String, String> values(InvoiceReportRow r, Locale locale) {
        var v = new HashMap<>(amounts(r.amounts()));
        v.put("projectName", text(r.projectName()));
        v.put("fullName", text(r.fullName()));
        v.put("customerName", text(r.customerName()));
        v.put("subjectName", text(r.subjectName()));
        v.put("id", text(r.id()));
        v.put("invoiceNo", text(r.invoiceNo()));
        v.put("invoiceDate", text(r.invoiceDate()).replace('T', ' '));
        v.put("buyerName", text(r.buyerName()));
        v.put("rowCount", Long.toString(r.rowCount()));
        v.put("sourceType", r.sourceType() == null ? "" : label(r.sourceType(), locale));
        v.put("invoiceStatus", r.invoiceStatus() == null ? "" : label(r.invoiceStatus(), locale));
        v.put("invoiceType", r.invoiceType() == null ? "" : label(r.invoiceType(), locale));
        return v;
    }

    private static Map<String, String> amounts(InvoiceReportAmounts a) {
        return Map.of(
                "receivableAmount",
                a.receivableAmount(),
                "receivedAmount",
                a.receivedAmount(),
                "invoicedAmount",
                a.invoicedAmount(),
                "uninvoicedAmount",
                a.uninvoicedAmount());
    }

    private static String text(String s) {
        return Objects.toString(s, "");
    }

    private static String label(String key, Locale locale) {
        return LocaleUtils.resolveMessage(
                "message.finance.report.invoice." + key,
                locale,
                ("zh".equals(locale.getLanguage()) ? ZH : EN).getOrDefault(key, key));
    }
}
