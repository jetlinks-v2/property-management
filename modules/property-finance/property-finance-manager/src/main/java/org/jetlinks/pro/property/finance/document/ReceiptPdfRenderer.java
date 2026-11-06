package org.jetlinks.pro.property.finance.document;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import org.hswebframework.web.i18n.LocaleUtils;
import org.jetlinks.pro.property.finance.service.DocumentStore;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.util.*;

/** 固定收据模板：只渲染后端冻结字段，转义用户文本并禁止HTML引擎读取外部资源。 */
@Component
public class ReceiptPdfRenderer {

    public byte[] render(DocumentStore.Detail detail, Locale locale, String issuerName) {
        validate(detail);
        var row = detail.document();
        var values = labels(locale);
        values.put("number", text(row.getDocumentNumber()));
        values.put("company", text(row.getCompanyName()));
        values.put("buyer", text(detail.buyer().name()));
        values.put("customer", text(row.getCustomerName()));
        values.put(
                "date",
                text(
                        java.time.LocalDateTime.parse(row.getIssuedAt())
                                .format(
                                        java.time.format.DateTimeFormatter.ofPattern(
                                                "yyyy-MM-dd HH:mm"))));
        values.put("operator", text(issuerName));
        values.put("remark", text(row.getRemark()));
        values.put("amount", row.getAmount().toPlainString());
        values.put("upper", text(ReceiptMoney.upper(row.getAmount())));
        values.put("status", "VOIDED".equals(row.getStatus()) ? values.get("voided") : "");
        values.put(
                "amountLabel",
                "AFTER_PAYMENT".equals(row.getIssueScene())
                        ? values.get("received")
                        : values.get("total"));
        values.put("rows", rows(detail));
        try {
            return DocumentPdfTemplate.render("receipt.html", values);
        } catch (Exception failure) {
            throw new org.hswebframework.web.exception.BusinessException(
                    "error.property_finance_receipt_print_failed", 500, failure);
        }
    }

    private static void validate(DocumentStore.Detail detail) {
        if (detail == null
                || detail.document() == null
                || detail.buyer() == null
                || detail.lines() == null
                || detail.lines().isEmpty()
                || detail.lines().size() > 1000) throw error("document_record_invalid", 409);
        var document = detail.document();
        if (!"RECEIPT".equals(document.getDocumentType())
                || !Set.of("ISSUED", "VOIDED").contains(Objects.toString(document.getStatus(), ""))
                || document.getAmount() == null
                || document.getIssuedAt() == null) throw error("document_record_invalid", 409);
        var total = BigDecimal.ZERO;
        for (var line : detail.lines()) {
            if (line == null
                    || line.line() == null
                    || line.snapshot() == null
                    || line.line().getAmount() == null
                    || line.snapshot().amount() == null)
                throw error("document_record_invalid", 409);
            BigDecimal amount;
            try {
                amount = new BigDecimal(line.snapshot().amount());
                ReceiptMoney.upper(amount);
            } catch (IllegalArgumentException | ArithmeticException invalid) {
                throw error("document_record_invalid", 409);
            }
            if (amount.compareTo(line.line().getAmount()) != 0)
                throw error("document_record_invalid", 409);
            total = total.add(amount);
        }
        if (total.compareTo(document.getAmount()) != 0) throw error("document_record_invalid", 409);
    }

    private static String rows(DocumentStore.Detail detail) {
        var rows = new StringBuilder();
        int index = 1;
        for (var item : detail.lines()) {
            var line = item.snapshot();
            rows.append("<tr><td>")
                    .append(index++)
                    .append("</td><td>")
                    .append(text(line.fullName()))
                    .append("</td><td>")
                    .append(text(line.subjectName()))
                    .append("</td><td>")
                    .append(
                            text(
                                    Objects.toString(line.startDate(), "")
                                            + " ~ "
                                            + Objects.toString(line.endDate(), "")))
                    .append("</td><td class=\"money\">")
                    .append(text(line.amount()))
                    .append("</td></tr>");
        }
        return rows.toString();
    }

    private static String text(String value) {
        return HtmlUtils.htmlEscape(Objects.toString(value, ""));
    }

    private static Map<String, String> labels(Locale locale) {
        var labels = new LinkedHashMap<String, String>();
        var defaults =
                Map.ofEntries(
                        Map.entry("title", "电子收据"), Map.entry("numberLabel", "收据号码"),
                        Map.entry("dateLabel", "开具日期"), Map.entry("buyerLabel", "收据抬头"),
                        Map.entry("customerLabel", "收费对象"), Map.entry("operatorLabel", "开具人"),
                        Map.entry("index", "序号"), Map.entry("space", "项目 / 房屋"),
                        Map.entry("subject", "收费科目"), Map.entry("period", "计费期间"),
                        Map.entry("money", "金额（元）"), Map.entry("total", "收据金额"),
                        Map.entry("received", "今收到 / 实收金额"), Map.entry("upperLabel", "人民币大写"),
                        Map.entry("voided", "已作废"), Map.entry("remarkLabel", "备注"),
                        Map.entry("footer", "本收据为系统电子凭证"), Map.entry("page", "页"));
        defaults.forEach(
                (key, fallback) ->
                        labels.put(
                                key,
                                text(
                                        LocaleUtils.resolveMessage(
                                                "message.finance.receipt.print." + key,
                                                locale,
                                                fallback))));
        return labels;
    }
}
