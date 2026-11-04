package org.jetlinks.pro.property.finance.document;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** 沿旧票据购方和差额凭证政策校验，不允许客户端覆盖来源税务快照。 */
@Component
@RequiredArgsConstructor
public class DocumentInputRules {
    private static final Set<String> VOUCHER_TYPES =
            Set.of("10", "11", "12", "13", "14", "15", "16", "05", "06", "09");
    private final DocumentRules rules;

    public DocumentIssueInput normalize(DocumentIssueInput input) {
        if (input == null
                || !Set.of("INVOICE", "RECEIPT")
                        .contains(Objects.toString(input.documentType(), ""))
                || input.items() == null
                || input.items().isEmpty()
                || input.items().size() > 1000
                || input.items().stream().anyMatch(Objects::isNull))
            throw error("document_input_invalid", 400);
        var company = text(input.companyId(), 64, true, "document_input_invalid");
        var invoice = "INVOICE".equals(input.documentType());
        var invoiceType = invoice && input.invoiceType() == null ? "1" : input.invoiceType();
        if (invoice && !Set.of("1", "2").contains(Objects.toString(invoiceType, ""))
                || !invoice && invoiceType != null) throw error("document_input_invalid", 400);
        var buyer = buyer(input.buyer(), invoice && "2".equals(invoiceType));
        var items = input.items().stream().map(item -> item(item, invoice)).toList();
        return new DocumentIssueInput(
                input.documentType(),
                company,
                buyer,
                invoiceType,
                text(input.remark(), 1000, false, "document_input_invalid"),
                items);
    }

    private DocumentIssueInput.Buyer buyer(DocumentIssueInput.Buyer input, boolean special) {
        if (input == null) throw error("document_buyer_invalid", 400);
        var name = text(input.name(), 255, true, "document_buyer_invalid");
        var tax = text(input.taxNum(), 50, special, "document_buyer_invalid");
        if (tax != null) {
            tax = tax.toUpperCase(Locale.ROOT);
            if (!tax.matches("[A-Z0-9]+")) throw error("document_buyer_invalid", 400);
        }
        var email = text(input.email(), 255, false, "document_buyer_invalid");
        if (email != null && !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
            throw error("document_buyer_invalid", 400);
        return new DocumentIssueInput.Buyer(
                name,
                tax,
                text(input.phone(), 50, false, "document_buyer_invalid"),
                email,
                text(input.address(), 500, false, "document_buyer_invalid"),
                text(input.depositBank(), 100, false, "document_buyer_invalid"),
                text(input.bankAccount(), 100, false, "document_buyer_invalid"),
                text(input.extensionNumber(), 32, false, "document_buyer_invalid"));
    }

    private DocumentIssueInput.Item item(DocumentIssueInput.Item item, boolean invoice) {
        var type = Objects.toString(item.sourceType(), "");
        if (!Set.of("RECEIVABLE", "RECEIVED", "TEMPORARY", "TEMP_PAYMENT").contains(type))
            throw error("document_source_invalid", 400);
        var id = text(item.sourceId(), 64, true, "document_source_invalid");
        var amount = rules.amount(item.amount(), false);
        var method =
                item.collectionMethod() == null || item.collectionMethod().isBlank()
                        ? "FULL"
                        : item.collectionMethod().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("FULL", "DIFFERENTIAL").contains(method) || !invoice && !"FULL".equals(method))
            throw error("document_tax_invalid", 400);
        var difference =
                rules.amount(item.differenceAmount() == null ? "0" : item.differenceAmount(), true);
        if ("FULL".equals(method) && difference.signum() != 0
                || "DIFFERENTIAL".equals(method)
                        && (difference.signum() <= 0 || difference.compareTo(amount) >= 0))
            throw error("document_tax_invalid", 400);
        var voucher = voucher(method, difference, item.voucher());
        return new DocumentIssueInput.Item(
                type, id, amount.toPlainString(), method, difference.toPlainString(), voucher);
    }

    private DocumentIssueInput.DifferenceVoucher voucher(
            String method, BigDecimal difference, DocumentIssueInput.DifferenceVoucher input) {
        if ("FULL".equals(method)) {
            if (input != null) throw error("document_voucher_invalid", 400);
            return null;
        }
        if (input == null || !VOUCHER_TYPES.contains(Objects.toString(input.type(), "")))
            throw error("document_voucher_invalid", 400);
        var number = text(input.number(), 100, false, "document_voucher_invalid");
        var electronic =
                text(input.electronicInvoiceNumber(), 100, false, "document_voucher_invalid");
        var code = text(input.invoiceCode(), 100, false, "document_voucher_invalid");
        var invoice = text(input.invoiceNumber(), 100, false, "document_voucher_invalid");
        boolean invoiceVoucher = Set.of("10", "11").contains(input.type());
        if (invoiceVoucher && electronic == null && (code == null || invoice == null)
                || !invoiceVoucher && number == null) throw error("document_voucher_invalid", 400);
        var date = text(input.date(), 10, true, "document_voucher_invalid");
        try {
            if (!LocalDate.parse(date).toString().equals(date))
                throw error("document_voucher_invalid", 400);
        } catch (RuntimeException invalid) {
            throw error("document_voucher_invalid", 400);
        }
        var amount = rules.amount(input.amount(), false);
        if (amount.compareTo(difference) < 0) throw error("document_voucher_invalid", 400);
        return new DocumentIssueInput.DifferenceVoucher(
                input.type(),
                number,
                electronic,
                code,
                invoice,
                date,
                amount.toPlainString(),
                text(input.remark(), 1000, "09".equals(input.type()), "document_voucher_invalid"),
                text(input.attachmentFileId(), 128, true, "document_voucher_invalid"),
                text(input.attachmentFileName(), 255, true, "document_voucher_invalid"));
    }

    private static String text(String value, int maximum, boolean required, String errorKey) {
        var result = value == null || value.isBlank() ? null : value.trim();
        if (required && result == null || result != null && result.length() > maximum)
            throw error(errorKey, 400);
        return result;
    }
}
