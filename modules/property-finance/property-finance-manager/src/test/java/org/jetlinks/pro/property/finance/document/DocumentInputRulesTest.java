package org.jetlinks.pro.property.finance.document;

import static org.junit.jupiter.api.Assertions.*;

import org.jetlinks.pro.property.finance.calculation.ReceivableTaxCalculator;
import org.junit.jupiter.api.Test;

import java.util.*;

class DocumentInputRulesTest {
    private final DocumentInputRules policy =
            new DocumentInputRules(new DocumentRules(new ReceivableTaxCalculator()));

    static DocumentIssueInput.Buyer buyer(String name, String tax) {
        return new DocumentIssueInput.Buyer(
                name,
                tax,
                "19123288779",
                "a@b.cn",
                " 重庆 ",
                "测试银行",
                "62220000",
                "001",
                (tax == null ? "PERSONAL" : "ENTERPRISE"));
    }

    static DocumentIssueInput.DifferenceVoucher voucher(String type) {
        return new DocumentIssueInput.DifferenceVoucher(
                type,
                "P001",
                "E001",
                null,
                null,
                "2024-02-15",
                "10.00",
                "扣除凭证说明",
                "file-id",
                "凭证.pdf");
    }

    static DocumentIssueInput input(
            String type,
            String invoiceType,
            DocumentIssueInput.Buyer buyer,
            DocumentIssueInput.Item item) {
        return new DocumentIssueInput(type, "company", buyer, invoiceType, "说明", List.of(item));
    }

    static DocumentIssueInput.Item item(
            String amount,
            String method,
            String difference,
            DocumentIssueInput.DifferenceVoucher voucher) {
        return new DocumentIssueInput.Item(
                "RECEIVABLE", "bill", amount, method, difference, voucher);
    }

    @Test
    void titleTypeControlsTaxNumberForOrdinaryAndSpecialInvoices() {
        var item = item("50", "FULL", "0", null);
        for (var type : List.of("1", "2")) {
            var enterprise =
                    new DocumentIssueInput.Buyer(
                            "企业", null, null, null, null, null, null, null, "ENTERPRISE");
            assertThrows(
                    RuntimeException.class,
                    () -> policy.normalize(input("INVOICE", type, enterprise, item)));
        }
        var personal =
                new DocumentIssueInput.Buyer(
                        "个人", null, null, null, null, null, null, null, "PERSONAL");
        assertEquals(
                "PERSONAL",
                policy.normalize(input("INVOICE", "1", personal, item)).buyer().titleType());
        assertThrows(
                RuntimeException.class,
                () -> policy.normalize(input("INVOICE", "2", personal, item)));
        var missing =
                new DocumentIssueInput.Buyer(
                        "未知抬头", null, null, null, null, null, null, null, null);
        assertThrows(
                RuntimeException.class,
                () -> policy.normalize(input("INVOICE", "1", missing, item)));
        assertNull(policy.normalize(input("RECEIPT", null, missing, item)).buyer().titleType());
    }

    @Test
    void normalizesAmountsBuyerAndDefaultFullWithoutAcceptingTaxOverrides() {
        var result =
                policy.normalize(
                        input(
                                "INVOICE",
                                "1",
                                buyer(" 购方 ", " ab123 "),
                                item("50", null, null, null)));
        assertEquals("购方", result.buyer().name());
        assertEquals("AB123", result.buyer().taxNum());
        assertEquals("重庆", result.buyer().address());
        assertEquals("50.00", result.items().get(0).amount());
        assertEquals("FULL", result.items().get(0).collectionMethod());
        assertEquals("0.00", result.items().get(0).differenceAmount());
        assertThrows(UnsupportedOperationException.class, () -> result.items().clear());
    }

    @Test
    void specialInvoiceRequiresBuyerTaxAndOtherInputsRemainBounded() {
        assertThrows(
                RuntimeException.class,
                () ->
                        policy.normalize(
                                input(
                                        "INVOICE",
                                        "2",
                                        buyer("企业", null),
                                        item("1", "FULL", "0", null))));
        assertThrows(
                RuntimeException.class,
                () ->
                        policy.normalize(
                                input(
                                        "INVOICE",
                                        "9",
                                        buyer("企业", "ABC"),
                                        item("1", "FULL", "0", null))));
        assertThrows(
                RuntimeException.class,
                () ->
                        policy.normalize(
                                input(
                                        "INVOICE",
                                        "1",
                                        buyer(" ", null),
                                        item("1", "FULL", "0", null))));
        for (var amount : List.of("0", "-1", "1.001", "1e2", "1000000000000000000.001"))
            assertThrows(
                    RuntimeException.class,
                    () ->
                            policy.normalize(
                                    input(
                                            "INVOICE",
                                            "1",
                                            buyer("购方", null),
                                            item(amount, "FULL", "0", null))));
    }

    @Test
    void differentialRequiresPositiveDeductionAndCompleteSupportedVoucher() {
        for (var type : List.of("10", "11", "12", "13", "14", "15", "16", "05", "06", "09")) {
            var result =
                    policy.normalize(
                            input(
                                    "INVOICE",
                                    "1",
                                    buyer("企业", null),
                                    item("50", "DIFFERENTIAL", "10", voucher(type))));
            assertEquals(type, result.items().get(0).voucher().type());
        }
        for (var difference : List.of("0", "50", "51", "-1", "10.001"))
            assertThrows(
                    RuntimeException.class,
                    () ->
                            policy.normalize(
                                    input(
                                            "INVOICE",
                                            "1",
                                            buyer("企业", null),
                                            item(
                                                    "50",
                                                    "DIFFERENTIAL",
                                                    difference,
                                                    voucher("10")))));
        assertThrows(
                RuntimeException.class,
                () ->
                        policy.normalize(
                                input(
                                        "INVOICE",
                                        "1",
                                        buyer("企业", null),
                                        item("50", "DIFFERENTIAL", "10", null))));
        assertThrows(
                RuntimeException.class,
                () ->
                        policy.normalize(
                                input(
                                        "INVOICE",
                                        "1",
                                        buyer("企业", null),
                                        item("50", "FULL", "0", voucher("10")))));
        assertThrows(
                RuntimeException.class,
                () ->
                        policy.normalize(
                                input(
                                        "RECEIPT",
                                        null,
                                        buyer("企业", null),
                                        item("50", "DIFFERENTIAL", "10", voucher("10")))));
    }

    @Test
    void malformedVoucherDatesNumbersAttachmentsAndInsufficientAmountsFail() {
        var valid = voucher("10");
        var invalid =
                List.of(
                        new DocumentIssueInput.DifferenceVoucher(
                                "99", "p", "e", null, null, valid.date(), "10", "说明", "f", "a.pdf"),
                        new DocumentIssueInput.DifferenceVoucher(
                                "10",
                                null,
                                null,
                                null,
                                null,
                                valid.date(),
                                "10",
                                null,
                                "f",
                                "a.pdf"),
                        new DocumentIssueInput.DifferenceVoucher(
                                "12",
                                null,
                                "e",
                                null,
                                null,
                                valid.date(),
                                "10",
                                null,
                                "f",
                                "a.pdf"),
                        new DocumentIssueInput.DifferenceVoucher(
                                "09",
                                "p",
                                null,
                                null,
                                null,
                                valid.date(),
                                "10",
                                null,
                                "f",
                                "a.pdf"),
                        new DocumentIssueInput.DifferenceVoucher(
                                "10", "p", "e", null, null, "2024-02-30", "10", null, "f", "a.pdf"),
                        new DocumentIssueInput.DifferenceVoucher(
                                "10",
                                "p",
                                "e",
                                null,
                                null,
                                valid.date(),
                                "9.99",
                                null,
                                "f",
                                "a.pdf"),
                        new DocumentIssueInput.DifferenceVoucher(
                                "10",
                                "p",
                                "e",
                                null,
                                null,
                                valid.date(),
                                "10",
                                null,
                                null,
                                "a.pdf"));
        for (var v : invalid)
            assertThrows(
                    RuntimeException.class,
                    () ->
                            policy.normalize(
                                    input(
                                            "INVOICE",
                                            "1",
                                            buyer("企业", null),
                                            item("50", "DIFFERENTIAL", "10", v))));
    }
}
