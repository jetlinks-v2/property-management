package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.CashierPersistenceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.receivable.AdjustmentInput;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.UUID;

/** 真PG核对结转内部减免的版本、权限和审计事务，不能先减免后静默丢失后续流水。 */
@SpringBootTest(
        classes = CarryForwardReductionPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CarryForwardReductionPersistenceTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        HouseSettingPersistenceTest.database(r);
    }

    @Autowired ReceivableAdjustmentService adjustments;
    @Autowired ReactiveRepository<ReceivableEntity, String> bills;
    @Autowired ReactiveRepository<ReceivableLogEntity, String> logs;
    @Autowired HouseArchiveSource archive;
    @Autowired CashierConfiguration configuration;
    CashierPersistenceTest fixture;

    @BeforeEach
    void setup() {
        fixture = new CashierPersistenceTest();
        fixture.archive = archive;
        fixture.configuration = configuration;
        fixture.bills = bills;
        fixture.setup();
    }

    AdjustmentInput reduction(ReceivableEntity bill, String amount) {
        var i = new AdjustmentInput();
        i.setVersion(bill.getVersion());
        i.setAdjustmentMode("REDUCTION");
        i.setFeeReductionAmount(amount);
        i.setReason("预收自动结转减免");
        return i;
    }

    @Test
    void partialCollectionIsPreservedAndReductionAndAuditPublishTogether() {
        var bill = fixture.bill("carry-reduction");
        bills.createUpdate()
                .set("paidAmount", new BigDecimal("20.00"))
                .set("arrears", new BigDecimal("80.00"))
                .where("id", bill.getId())
                .execute()
                .block(WAIT);
        var input = reduction(bill, "10.00");
        adjustments
                .reduceForCarry(fixture.project, bill.getId(), input, DATE)
                .contextWrite(fixture.allowed())
                .block(WAIT);
        var actual = bills.findById(bill.getId()).block(WAIT);
        assertEquals(new BigDecimal("20.00"), actual.getPaidAmount());
        assertEquals(new BigDecimal("70.00"), actual.getArrears());
        assertEquals(new BigDecimal("10.00"), actual.getFeeReductionAmount());
        assertEquals(bill.getVersion() + 1, actual.getVersion());
        var log = logs.createQuery().where("receivableId", bill.getId()).fetchOne().block(WAIT);
        assertNotNull(log);
        assertEquals(actual.getVersion(), log.getAfterVersion());
        assertTrue(log.getDetailJson().contains("REDUCTION"));
        fixture.rejects(
                adjustments.reduceForCarry(fixture.project, bill.getId(), input, DATE), 409);
        assertEquals(1, logs.createQuery().where("receivableId", bill.getId()).count().block(WAIT));
    }

    @Test
    void failedAuditInsertRollsBackReductionAndVersion() throws Exception {
        var bill = fixture.bill("carry-rollback");
        var constraint = "carry_log_" + UUID.randomUUID().toString().replace("-", "");
        assertEquals(
                0,
                HouseSettingPersistenceTest.DB
                        .execInContainer(
                                "psql",
                                "-U",
                                "postgres",
                                "-d",
                                "setting_test",
                                "-c",
                                "ALTER TABLE pm_fin_receivable_log ADD CONSTRAINT "
                                        + constraint
                                        + " CHECK (receivable_id <> '"
                                        + bill.getId()
                                        + "')")
                        .getExitCode());
        try {
            StepVerifier.create(
                            adjustments
                                    .reduceForCarry(
                                            fixture.project,
                                            bill.getId(),
                                            reduction(bill, "10.00"),
                                            DATE)
                                    .contextWrite(fixture.allowed()))
                    .expectError()
                    .verify(WAIT);
            var unchanged = bills.findById(bill.getId()).block(WAIT);
            assertEquals(bill.getVersion(), unchanged.getVersion());
            assertEquals(new BigDecimal("0.00"), unchanged.getFeeReductionAmount());
            assertEquals(new BigDecimal("100.00"), unchanged.getArrears());
            assertEquals(
                    0, logs.createQuery().where("receivableId", bill.getId()).count().block(WAIT));
        } finally {
            assertEquals(
                    0,
                    HouseSettingPersistenceTest.DB
                            .execInContainer(
                                    "psql",
                                    "-U",
                                    "postgres",
                                    "-d",
                                    "setting_test",
                                    "-c",
                                    "ALTER TABLE pm_fin_receivable_log DROP CONSTRAINT "
                                            + constraint)
                            .getExitCode());
        }
    }

    @Test
    void forgedProjectAndInvoicedSourceCannotReceiveAutomaticReduction() {
        var bill = fixture.bill("carry-scope");
        fixture.rejects(
                adjustments.reduceForCarry(
                        "another-project", bill.getId(), reduction(bill, "10.00"), DATE),
                404);
        bills.createUpdate()
                .set("invoiceOccupiedAmount", new BigDecimal("0.01"))
                .where("id", bill.getId())
                .execute()
                .block(WAIT);
        fixture.rejects(
                adjustments.reduceForCarry(
                        fixture.project, bill.getId(), reduction(bill, "10.00"), DATE),
                409);
        assertEquals(
                new BigDecimal("0.00"),
                bills.findById(bill.getId()).block(WAIT).getFeeReductionAmount());
    }

    @Configuration
    @Import(CashierPersistenceTest.Config.class)
    static class Config {}
}
