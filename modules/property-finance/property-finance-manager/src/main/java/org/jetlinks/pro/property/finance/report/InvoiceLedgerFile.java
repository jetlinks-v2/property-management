package org.jetlinks.pro.property.finance.report;

import java.util.*;

/** 发票台账按票面快照导出，金额不乘以房屋或来源行数。 */
public final class InvoiceLedgerFile {
    private InvoiceLedgerFile() {}

    public static FinanceReportFile.Data data(InvoiceLedgerResult result, Locale locale) {
        boolean zh = "zh".equals(locale.getLanguage());
        var columns =
                List.of(
                        "projectName",
                        "invoiceNo",
                        "ticketColor",
                        "status",
                        "originalInvoiceNo",
                        "buyerName",
                        "buyerTaxNum",
                        "companyName",
                        "sellerTaxNum",
                        "fullName",
                        "subjectName",
                        "invoiceDate",
                        "invoiceType",
                        "collectionMethod",
                        "invoicedAmount",
                        "netAmount",
                        "taxAmount",
                        "differenceAmount",
                        "outputTax",
                        "modifierName",
                        "remark");
        String[] labels =
                zh
                        ? new String[] {
                            "项目",
                            "发票号码",
                            "蓝红类型",
                            "发票状态",
                            "关联原票",
                            "购方抬头",
                            "购方税号",
                            "销售方",
                            "销售方税号",
                            "房屋",
                            "收费科目",
                            "开票时间",
                            "发票类型",
                            "征收方式",
                            "价税合计（元）",
                            "不含税金额（元）",
                            "票面税额（元）",
                            "差额扣除（元）",
                            "销项税额（六位）",
                            "开票人",
                            "备注"
                        }
                        : new String[] {
                            "Project",
                            "Invoice number",
                            "Color",
                            "Status",
                            "Original invoice",
                            "Buyer",
                            "Buyer tax number",
                            "Seller",
                            "Seller tax number",
                            "Space",
                            "Subject",
                            "Issued at",
                            "Type",
                            "Collection method",
                            "Gross",
                            "Net",
                            "Tax",
                            "Deduction",
                            "Output tax (6 decimals)",
                            "Issuer",
                            "Remark"
                        };
        var names = new LinkedHashMap<String, String>();
        for (int n = 0; n < columns.size(); n++) names.put(columns.get(n), labels[n]);
        var rows =
                result.page().getData().stream()
                        .map(
                                row -> {
                                    var values = new LinkedHashMap<String, String>();
                                    for (var key : columns)
                                        values.put(key, Objects.toString(row.get(key), ""));
                                    var amounts = (Map<?, ?>) row.get("amounts");
                                    amounts.forEach(
                                            (k, v) -> values.put(k.toString(), v.toString()));
                                    for (var key :
                                            List.of(
                                                    "ticketColor",
                                                    "status",
                                                    "invoiceType",
                                                    "collectionMethod"))
                                        values.put(
                                                key,
                                                Arrays.stream(values.get(key).split(" / "))
                                                        .map(v -> display(v, zh))
                                                        .collect(
                                                                java.util.stream.Collectors.joining(
                                                                        " / ")));
                                    String time = values.get("invoiceDate").replace('T', ' ');
                                    values.put(
                                            "invoiceDate",
                                            time.length() > 16 ? time.substring(0, 16) : time);
                                    return (Map<String, String>) values;
                                })
                        .toList();
        return new FinanceReportFile.Data(
                zh ? "发票台账" : "Invoice ledger",
                zh
                        ? "按发票逐张统计，红票为负；已红冲蓝票及其红票均保留，不代表现金退款。"
                        : "One row per invoice; red invoices are negative, without cash refunds.",
                zh ? "全筛选范围合计" : "Filtered total",
                columns,
                names,
                Set.of("invoicedAmount", "netAmount", "taxAmount", "differenceAmount", "outputTax"),
                rows,
                result.summary());
    }

    private static String display(String v, boolean zh) {
        return Map.ofEntries(
                        Map.entry("BLUE", zh ? "蓝票" : "Blue"),
                        Map.entry("RED", zh ? "红票" : "Red"),
                        Map.entry("SIMULATED_ISSUED", zh ? "已开具" : "Issued"),
                        Map.entry("SIMULATED_REVERSED", zh ? "已红冲" : "Reversed"),
                        Map.entry("SIMULATED_RED", zh ? "红字发票" : "Red invoice"),
                        Map.entry("1", zh ? "普通发票" : "Ordinary"),
                        Map.entry("2", zh ? "专用发票" : "Special"),
                        Map.entry("FULL", zh ? "全额征收" : "Full"),
                        Map.entry("DIFFERENTIAL", zh ? "差额征收" : "Differential"))
                .getOrDefault(v, v);
    }
}
