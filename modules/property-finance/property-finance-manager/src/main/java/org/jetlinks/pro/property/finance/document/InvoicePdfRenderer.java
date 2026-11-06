package org.jetlinks.pro.property.finance.document;

import org.hswebframework.web.i18n.LocaleUtils;
import org.jetlinks.pro.property.finance.service.DocumentStore;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 模拟发票采用独立票面，不复制示例中的税务监制章、二维码或真实票号。 */
public final class InvoicePdfRenderer {
    private InvoicePdfRenderer() {}

    public static byte[] render(DocumentStore.Detail detail, Locale locale) {
        var row = detail.document();
        var values = new LinkedHashMap<String, String>();
        Map.ofEntries(
                        Map.entry("title", "电子发票"), Map.entry("notice", "仅供物业业务核对，不作为税务发票使用"),
                        Map.entry("numberLabel", "票据号码"), Map.entry("dateLabel", "开票日期"),
                        Map.entry("buyerLabel", "购买方信息"), Map.entry("sellerLabel", "销售方信息"),
                        Map.entry("nameLabel", "名称"), Map.entry("taxLabel", "统一社会信用代码 / 纳税人识别号"),
                        Map.entry("goodsLabel", "项目名称"), Map.entry("houseLabel", "房屋 / 计费期间"),
                        Map.entry("amountLabel", "金额"), Map.entry("rateLabel", "税率 / 征收率"),
                        Map.entry("taxAmountLabel", "税额"), Map.entry("totalLabel", "合计"),
                        Map.entry("upperLabel", "价税合计（大写）"), Map.entry("lowerLabel", "小写"),
                        Map.entry("remarkLabel", "备注"), Map.entry("operatorLabel", "开票人"),
                        Map.entry("regular", "普通发票"), Map.entry("special", "专用发票"),
                        Map.entry("red", "红字票据"), Map.entry("reversed", "已红冲"))
                .forEach(
                        (key, fallback) ->
                                values.put(
                                        key,
                                        text(
                                                LocaleUtils.resolveMessage(
                                                        "message.finance.invoice.print." + key,
                                                        locale,
                                                        fallback))));
        values.put("kind", values.get("2".equals(row.getInvoiceType()) ? "special" : "regular"));
        values.put("number", text(row.getDocumentNumber()));
        values.put(
                "date",
                LocalDateTime.parse(row.getIssuedAt())
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        values.put("buyer", text(detail.buyer().name()));
        values.put("buyerTax", text(detail.buyer().taxNum()));
        values.put("seller", text(row.getCompanyName()));
        values.put("sellerTax", text(row.getSellerTaxNum()));
        values.put(
                "status",
                "SIMULATED_RED".equals(row.getStatus())
                        ? values.get("red")
                        : "SIMULATED_REVERSED".equals(row.getStatus())
                                ? values.get("reversed")
                                : "");
        var lines = new StringBuilder();
        for (var item : detail.lines()) {
            var line = item.snapshot();
            lines.append("<tr><td>")
                    .append(text(line.goodsName()))
                    .append("<div class=\"minor\">")
                    .append(text(line.subjectName()))
                    .append("</div></td><td>")
                    .append(text(line.fullName()))
                    .append("<div class=\"minor\">")
                    .append(text(line.startDate()))
                    .append(" ~ ")
                    .append(text(line.endDate()))
                    .append("</div></td><td class=\"money\">")
                    .append(text(line.netAmount()))
                    .append("</td><td class=\"money\">")
                    .append(rate(line.taxRate()))
                    .append("</td><td class=\"money\">")
                    .append(text(line.taxAmount()))
                    .append("</td></tr>");
        }
        values.put("rows", lines.toString());
        values.put("net", row.getNetAmount().toPlainString());
        values.put("tax", row.getTaxAmount().toPlainString());
        values.put("amount", row.getAmount().toPlainString());
        values.put(
                "upper",
                text(
                        (row.getAmount().signum() < 0 ? "负" : "")
                                + ReceiptMoney.upper(row.getAmount().abs())));
        values.put("remark", text(row.getRemark()));
        values.put(
                "operator",
                text(
                        row instanceof DocumentLedgerRow ledger
                                ? ledger.getIssuerName()
                                : row.getModifierName()));
        try {
            return DocumentPdfTemplate.render("invoice.html", values);
        } catch (Exception cause) {
            throw new org.hswebframework.web.exception.BusinessException(
                    "error.property_finance_document_pdf_failed", 500, cause);
        }
    }

    private static String rate(String value) {
        return new BigDecimal(value).movePointRight(2).stripTrailingZeros().toPlainString() + "%";
    }

    private static String text(String value) {
        return HtmlUtils.htmlEscape(Objects.toString(value, ""));
    }
}
