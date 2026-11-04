package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.alibaba.fastjson.JSON;

import org.jetlinks.pro.property.finance.calculation.ReceivableTaxCalculator;
import org.jetlinks.pro.property.finance.document.DocumentRules;
import org.jetlinks.pro.property.finance.entity.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.*;

/** 真实PG读取规范来源，历史名称税率与当前基础档案/标准无关，混合支付按主单共享额度。 */
@SpringBootTest(
        classes = DocumentSourcePersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DocumentSourcePersistenceTest extends TemporaryPersistenceTest {
    @Autowired DocumentSourceService documents;

    static String tax() {
        return JSON.toJSONString(
                Map.of(
                        "taxItemId",
                        "tax",
                        "companyId",
                        "company",
                        "companyName",
                        "原开票公司",
                        "sellerTaxNum",
                        "915001000000000001",
                        "goodsCode",
                        "3040801",
                        "goodsName",
                        "原物业服务",
                        "taxRate",
                        "0.06",
                        "includingTaxFlag",
                        1));
    }

    @BeforeEach
    void completeFrozenTax() {
        fixture.cashier = cashier;
        standards
                .createUpdate()
                .set("taxSnapshotJson", tax())
                .where("id", setting.standardId)
                .execute()
                .block(WAIT);
    }

    @Test
    void unpaidReceivableUsesFrozenTaxAndExistingOccupationWithoutCurrentCustomerLookup() {
        var row = documentBill();
        bills.createUpdate()
                .set("invoiceOccupiedAmount", new BigDecimal("74.51"))
                .where("id", row.getId())
                .execute()
                .block(WAIT);
        clearInvocations(archive);
        var source = load("RECEIVABLE", row.getId());
        assertEquals("RECEIVABLE", source.canonical().type());
        assertEquals(row.getId(), source.canonical().id());
        assertEquals(new BigDecimal("100.00"), source.allowedAmount());
        assertEquals(new BigDecimal("0.00"), source.paidAmount());
        assertEquals(new BigDecimal("25.49"), source.availableAmount());
        assertEquals("龚超", source.customerName());
        assertEquals("原开票公司", source.tax().companyName());
        assertEquals("0.06", source.tax().taxRate());
        assertNull(source.paidAt());
        verifyNoInteractions(archive);
    }

    @Test
    void differentReceiptsOfSameBillNormalizeAndCannotBeSelectedTwice() {
        var row = documentBill();
        var firstInput = fixture.input(row, "40.00");
        var first =
                cashier.collect(project, fixture.submission(firstInput), DATE)
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        row = bills.findById(row.getId()).block(WAIT);
        var secondInput = fixture.input(row, "60.00");
        var second =
                cashier.collect(project, fixture.submission(secondInput), DATE)
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        var a = load("RECEIVED", first.lines().get(0).getId());
        var b = load("RECEIVED", second.lines().get(0).getId());
        assertEquals(a.canonical(), b.canonical());
        assertEquals(new BigDecimal("100.00"), a.paidAmount());
        assertEquals(new BigDecimal("100.00"), a.availableAmount());
        fixture.rejects(
                documents.load(
                        project,
                        List.of(
                                new DocumentSourceService.Reference("RECEIVABLE", row.getId()),
                                new DocumentSourceService.Reference(
                                        "RECEIVED", first.lines().get(0).getId())),
                        false),
                409);
    }

    @Test
    void temporaryMixedPaymentsUseNetUnrefundedAmountAndFrozenTaxOnly() {
        var payment = collect(input());
        var cash = payment.lines().get(0);
        lines.createUpdate()
                .set("refundedAmount", new BigDecimal("10.00"))
                .set("invoiceOccupiedAmount", new BigDecimal("20.00"))
                .where("id", cash.getId())
                .execute()
                .block(WAIT);
        standards
                .createUpdate()
                .set("taxSnapshotJson", "{}")
                .set("standardName", "新的标准名称")
                .where("id", setting.standardId)
                .execute()
                .block(WAIT);
        var a = load("TEMP_PAYMENT", payment.payment().getId());
        var b = load("RECEIVED", payment.lines().get(1).getId());
        assertEquals(a.canonical(), b.canonical());
        assertEquals(new BigDecimal("90.01"), a.allowedAmount());
        assertEquals(new BigDecimal("70.01"), a.availableAmount());
        assertEquals("原物业服务", a.tax().goodsName());
        assertEquals("面积月费", a.standardName());
        assertEquals(2, a.paymentLines().size());
        assertEquals(0, bills.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void missingSnapshotDeletedOrSplitAndCrossProjectNeverAppearAsAvailable() {
        var row = fixture.bill("missing-tax");
        fixture.rejects(documents.load(project, references("RECEIVABLE", row.getId()), false), 409);
        row = documentBill();
        fixture.rejects(
                documents.load("another-project", references("RECEIVABLE", row.getId()), false),
                404);
        bills.createUpdate().set("split", true).where("id", row.getId()).execute().block(WAIT);
        fixture.rejects(documents.load(project, references("RECEIVABLE", row.getId()), false), 409);
        bills.createUpdate()
                .set("split", false)
                .set("deleted", true)
                .where("id", row.getId())
                .execute()
                .block(WAIT);
        fixture.rejects(documents.load(project, references("RECEIVABLE", row.getId()), false), 404);
    }

    @Test
    void fakeTemporaryRootOrMixedHouseLinesAreRejected() {
        var row = documentBill();
        var input = fixture.input(row, "10.00");
        var ordinary =
                cashier.collect(project, fixture.submission(input), DATE)
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        fixture.rejects(
                documents.load(
                        project, references("TEMP_PAYMENT", ordinary.payment().getId()), false),
                409);
        var temporary = collect(input());
        lines.createUpdate()
                .set("spaceId", "another-room")
                .where("id", temporary.lines().get(1).getId())
                .execute()
                .block(WAIT);
        fixture.rejects(
                documents.load(
                        project, references("TEMP_PAYMENT", temporary.payment().getId()), false),
                409);
    }

    @Test
    void spacePermissionAndAuthenticationCannotBeBypassedBySourceAlias() {
        var row = documentBill();
        var restricted = fixture.allowed();
        var holder =
                restricted.<org.jetlinks.pro.assets.AssetsHolder>get(
                        org.jetlinks.pro.assets.AssetsHolder.class);
        when(holder.assertPermission(anyString(), anyCollection(), any()))
                .thenAnswer(
                        call ->
                                call.<Collection<String>>getArgument(1).contains("room")
                                        ? Mono.error(FinanceScope.error("permission_denied", 403))
                                        : Mono.empty());
        reactor.test.StepVerifier.create(
                        documents
                                .load(project, references("RECEIVABLE", row.getId()), false)
                                .contextWrite(restricted))
                .expectErrorMatches(error -> TaxItemPersistenceTest.status(error, 403))
                .verify(WAIT);
        reactor.test.StepVerifier.create(
                        documents.load(project, references("RECEIVABLE", row.getId()), false))
                .expectError()
                .verify(WAIT);
    }

    @Test
    void corruptedReceiptDimensionsCompanyAndAmountCannotSupplyDocumentQuota() {
        var row = documentBill();
        var payment =
                cashier.collect(project, fixture.submission(fixture.input(row, "40.00")), DATE)
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        var lineId = payment.lines().get(0).getId();
        for (var field :
                List.of("spaceId", "customerId", "subjectId", "standardId", "sourceType")) {
            var original =
                    new org.springframework.beans.BeanWrapperImpl(payment.lines().get(0))
                            .getPropertyValue(field);
            lines.createUpdate().set(field, "wrong").where("id", lineId).execute().block(WAIT);
            fixture.rejects(
                    documents.load(project, references("RECEIVABLE", row.getId()), false), 409);
            lines.createUpdate().set(field, original).where("id", lineId).execute().block(WAIT);
        }
        payments.createUpdate()
                .set("companyId", "wrong-company")
                .where("id", payment.payment().getId())
                .execute()
                .block(WAIT);
        fixture.rejects(documents.load(project, references("RECEIVABLE", row.getId()), false), 409);
        payments.createUpdate()
                .set("companyId", "company")
                .where("id", payment.payment().getId())
                .execute()
                .block(WAIT);
        lines.createUpdate()
                .set("amount", new BigDecimal("39.99"))
                .where("id", lineId)
                .execute()
                .block(WAIT);
        fixture.rejects(documents.load(project, references("RECEIVABLE", row.getId()), false), 409);
    }

    @Test
    void realRefundReconcilesNetPaidAndUnpaidInvoiceBeforePaymentPath() {
        var row = documentBill();
        var payment =
                cashier.collect(project, fixture.submission(fixture.input(row, "40.00")), DATE)
                        .contextWrite(fixture.allowed())
                        .block(WAIT);
        var input = new org.jetlinks.pro.property.finance.payment.RefundInput();
        input.setCompanyId("company");
        input.setMethod("1");
        input.setAmount(new BigDecimal("40.00"));
        input.setRefundedAt("2024-02-15T13:00:00");
        input.setReceiverName("退款客户");
        input.setReason("测试退还原收款");
        input.setItems(
                List.of(
                        new org.jetlinks.pro.property.finance.payment.RefundInput.Item(
                                payment.lines().get(0).getId(),
                                payment.lines().get(0).getVersion())));
        var preview =
                refunds.preview(project, input, DATE).contextWrite(fixture.allowed()).block(WAIT);
        refunds.confirm(
                        project,
                        new RefundService.Submission(
                                UUID.randomUUID().toString(), preview.token(), input),
                        DATE)
                .contextWrite(fixture.allowed())
                .block(WAIT);
        var source = load("RECEIVABLE", row.getId());
        assertEquals(new BigDecimal("0.00"), source.paidAmount());
        assertEquals(new BigDecimal("100.00"), source.availableAmount());
        assertNull(source.paidAt());
        assertEquals(new BigDecimal("40.00"), source.paymentLines().get(0).getRefundedAmount());
    }

    private ReceivableEntity documentBill() {
        var row = fixture.bill("document");
        var snapshot = JSON.parseObject(row.getSnapshotJson());
        snapshot.getJSONObject("standard").put("id", row.getStandardId());
        snapshot.getJSONObject("standard").put("subjectId", row.getSubjectId());
        snapshot.getJSONObject("standard").put("companyId", "company");
        snapshot.getJSONObject("standard").put("taxItemId", "tax");
        snapshot.getJSONObject("standard").put("taxSnapshotJson", tax());
        row.setSnapshotJson(snapshot.toJSONString());
        bills.createUpdate()
                .set("snapshotJson", row.getSnapshotJson())
                .where("id", row.getId())
                .execute()
                .block(WAIT);
        return row;
    }

    private DocumentSourceService.Source load(String type, String id) {
        return documents
                .load(project, references(type, id), false)
                .contextWrite(fixture.allowed())
                .block(WAIT)
                .get(0);
    }

    private static List<DocumentSourceService.Reference> references(String type, String id) {
        return List.of(new DocumentSourceService.Reference(type, id));
    }

    @Configuration
    @Import({
        TemporaryPersistenceTest.Config.class,
        DocumentSourceService.class,
        DocumentRules.class,
        ReceivableTaxCalculator.class
    })
    static class Config {}
}
