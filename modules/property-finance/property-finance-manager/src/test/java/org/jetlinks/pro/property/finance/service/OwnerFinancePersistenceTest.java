package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.base.archive.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;

import reactor.core.publisher.Mono;

import java.util.*;

/** 真实PG验证业主试算与柜台同源、本人身份、复核过期/变更及不落账。 */
@SpringBootTest(
        classes = OwnerFinancePersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OwnerFinancePersistenceTest extends PrepayQuotePersistenceTest {
    @Autowired OwnerPrepayService ownerPrepay;
    @Autowired OwnerFinanceService ownerFinance;
    @Autowired OwnerHouseSource ownerHouses;
    @Autowired ReactiveRepository<FundAccountEntity, String> accounts;

    @BeforeEach
    void ownerContext() {
        reset(ownerHouses);
        var owner =
                new PropertyOwnerHouse(
                        "项目",
                        fixture.context("room"),
                        List.of(
                                new PropertySpaceInfo(
                                        "root", null, "root", "项目", "项目", null, null, false),
                                new PropertySpaceInfo(
                                        "room", "root", "root", "101", "项目/101", null, null,
                                        false)));
        when(ownerHouses.require(anyString(), anyString()))
                .thenReturn(Mono.error(FinanceScope.error("owner_room_denied", 403)));
        when(ownerHouses.require(fixture.project, "room")).thenReturn(Mono.just(owner));
    }

    OwnerPrepayService.Request request() {
        return new OwnerPrepayService.Request(settingId, null, 6, 0);
    }

    @Test
    void quoteAndReviewNeverPostMoneyAndUseOnlyCurrentPayer() {
        var beforeBills =
                bills.createQuery().where("projectId", fixture.project).count().block(WAIT);
        var beforePayments =
                payments.createQuery().where("projectId", fixture.project).count().block(WAIT);
        var quote = run(ownerPrepay.quote(fixture.project, "room", request(), DATE));
        assertEquals(6, quote.lines().size());
        assertEquals("龚超", quote.payerName());
        assertEquals(quote, run(ownerPrepay.revalidate(fixture.project, "room", quote.id(), DATE)));
        assertEquals(
                beforeBills,
                bills.createQuery().where("projectId", fixture.project).count().block(WAIT));
        assertEquals(
                beforePayments,
                payments.createQuery().where("projectId", fixture.project).count().block(WAIT));
        verifyNoInteractions(activitySource);
    }

    @Test
    void endedOrForeignRoomCannotReadQuoteAccountsOrBills() {
        reject(ownerPrepay.quote(fixture.project, "someone-else", request(), DATE), 403);
        reject(ownerFinance.accounts(fixture.project, "someone-else"), 403);
        reject(ownerFinance.bills(fixture.project, "someone-else", false, 0, 20), 403);
        var quote = run(ownerPrepay.quote(fixture.project, "room", request(), DATE));
        when(ownerHouses.require(fixture.project, "room"))
                .thenReturn(Mono.error(FinanceScope.error("owner_room_denied", 403)));
        reject(ownerPrepay.revalidate(fixture.project, "room", quote.id(), DATE), 403);
    }

    @Test
    void expiredQuoteAndChangedBillRequireNewCalculation() {
        var quote = run(ownerPrepay.quote(fixture.project, "room", request(), DATE));
        quotes.createUpdate().set("expiresAt", 1L).where("id", quote.id()).execute().block(WAIT);
        reject(ownerPrepay.revalidate(fixture.project, "room", quote.id(), DATE), 409);
        var current = run(ownerPrepay.quote(fixture.project, "room", request(), DATE));
        reject(
                ownerPrepay.revalidate(fixture.project, "room", current.id(), DATE.plusDays(1)),
                409);
    }

    @Test
    void anotherQuoteCreatorCannotRevalidate() {
        var quote = run(ownerPrepay.quote(fixture.project, "room", request(), DATE));
        quotes.createUpdate()
                .set("creatorId", "someone-else")
                .where("id", quote.id())
                .execute()
                .block(WAIT);
        reject(ownerPrepay.revalidate(fixture.project, "room", quote.id(), DATE), 404);
    }

    @Test
    void ownerOptionsUseOwnPayerAndTrustedAncestors() {
        var activity = activity(false);
        var result = run(ownerPrepay.options(fixture.project, "room", DATE));
        assertEquals(
                List.of("link"),
                result.get(0).payers().stream()
                        .map(PrepayOptionsService.Payer::relationshipId)
                        .toList());
        assertEquals(
                List.of(activity.getId()),
                result.get(0).activities().stream()
                        .map(PrepayOptionsService.Activity::id)
                        .toList());
        verifyNoInteractions(activitySource);
    }

    @Test
    void currentUserDoesNotSeeAnUnselectedBill() {
        var result = run(ownerFinance.bills(fixture.project, "room", false, 0, 20));
        assertTrue(result.total() > 0);
        var original =
                bills.createQuery().where("projectId", fixture.project).fetchOne().block(WAIT);
        var foreign = new ReceivableEntity();
        org.springframework.beans.BeanUtils.copyProperties(original, foreign);
        foreign.setId(UUID.randomUUID().toString());
        foreign.setBillKey(UUID.randomUUID().toString());
        foreign.setBillNo(
                org.jetlinks.pro.property.finance.payment.FinanceDocumentNumbers.next("YS"));
        foreign.setProjectId("foreign-project");
        foreign.setSpaceId("foreign-room");
        bills.insert(foreign).block(WAIT);
        assertEquals(
                result.total(),
                run(ownerFinance.bills(fixture.project, "room", false, 0, 20)).total());
        for (var bill : result.data()) {
            assertNotNull(bill.id());
            assertEquals(original.getSubjectName(), bill.subjectName());
        }
        var count =
                bills.createUpdate()
                        .set("customerId", "stranger")
                        .set("snapshotJson", "{}")
                        .where("projectId", fixture.project)
                        .execute()
                        .block(WAIT);
        assertTrue(count > 0);
        var changed =
                bills.createQuery().where("projectId", fixture.project).fetchOne().block(WAIT);
        assertEquals("stranger", changed.getCustomerId());
        assertEquals("{}", changed.getSnapshotJson());
        assertEquals(0, run(ownerFinance.bills(fixture.project, "room", false, 0, 20)).total());
    }

    @Test
    void ownerBillsKeepSubjectsForSamePeriodInBothUnpaidAndPaidLists() {
        var original =
                bills.createQuery().where("projectId", fixture.project).fetchOne().block(WAIT);
        var other = new ReceivableEntity();
        org.springframework.beans.BeanUtils.copyProperties(original, other);
        other.setId(UUID.randomUUID().toString());
        other.setBillKey(UUID.randomUUID().toString());
        other.setBillNo(null);
        org.jetlinks.pro.property.finance.payment.FinanceDocumentNumbers.assign(other);
        other.setSubjectName("停车费");
        bills.insert(other).block(WAIT);
        var unpaid = run(ownerFinance.bills(fixture.project, "room", false, 0, 50));
        assertTrue(unpaid.data().stream().anyMatch(b -> "停车费".equals(b.subjectName())));
        assertTrue(
                unpaid.data().stream()
                        .anyMatch(b -> original.getSubjectName().equals(b.subjectName())));
        bills.createUpdate()
                .set("paidAmount", other.getActualReceivableAmount())
                .set("arrears", java.math.BigDecimal.ZERO)
                .where("id", other.getId())
                .execute()
                .block(WAIT);
        var paid = run(ownerFinance.bills(fixture.project, "room", true, 0, 50));
        assertTrue(
                paid.data().stream()
                        .anyMatch(
                                b ->
                                        b.id().equals(other.getId())
                                                && "停车费".equals(b.subjectName())));
    }

    @Autowired ReactiveRepository<FundEntryEntity, String> fundEntries;

    @Test
    void roomEntriesMergeOwnAccountsWithStablePaginationAndExcludeOthers() {
        var customer = fixture.context("room").customers().get(0).customerId();
        var a = ownerFund(customer, "room", "ADVANCE", "2026-01-01T10:00:00");
        var b = ownerFund(customer, "room", "ADVANCE", "2026-01-02T10:00:00");
        ownerFund("other", "room", "ADVANCE", "2026-01-03T10:00:00");
        ownerFund(customer, "other-room", "ADVANCE", "2026-01-04T10:00:00");
        ownerFund(customer, "room", "DEPOSIT", "2026-01-05T10:00:00");
        var first = run(ownerFinance.roomEntries(fixture.project, "room", 0, 1));
        var second = run(ownerFinance.roomEntries(fixture.project, "room", 1, 1));
        assertEquals(2, first.total());
        assertEquals(List.of(b), first.data().stream().map(OwnerFinanceService.Entry::id).toList());
        assertEquals(
                List.of(a), second.data().stream().map(OwnerFinanceService.Entry::id).toList());
        reject(ownerFinance.roomEntries(fixture.project, "other-room", 0, 20), 403);
        accounts.createUpdate()
                .set("deleted", true)
                .where("projectId", fixture.project)
                .execute()
                .block(WAIT);
        assertEquals(0, run(ownerFinance.roomEntries(fixture.project, "room", 0, 20)).total());
    }

    String ownerFund(String customer, String room, String type, String date) {
        var a = new FundAccountEntity();
        a.setId(UUID.randomUUID().toString());
        a.setProjectId(fixture.project);
        a.setCompanyId("company");
        a.setCompanyName("公司");
        a.setType(type);
        a.setSpaceId(room);
        a.setFullName("项目/" + room);
        a.setCustomerId(customer);
        a.setRelationshipId("relation");
        a.setCustomerName("客户");
        a.setSubjectId(UUID.randomUUID().toString());
        a.setSubjectName("内部科目");
        a.setStandardId("standard");
        a.setStandardName("内部标准");
        a.setBalance(new java.math.BigDecimal("100"));
        a.setInvoiceOccupiedAmount(java.math.BigDecimal.ZERO);
        StandardPersistenceTest.initialize(a);
        accounts.insert(a).block(WAIT);
        var e = new FundEntryEntity();
        e.setId(UUID.randomUUID().toString());
        e.setProjectId(fixture.project);
        e.setSpaceId(room);
        e.setAccountId(a.getId());
        e.setTransactionId(UUID.randomUUID().toString());
        e.setAction("DEPOSIT");
        e.setCompanyId("company");
        e.setCompanyName("公司");
        e.setFullName("项目/" + room);
        e.setCustomerName("客户");
        e.setSubjectName("内部科目");
        e.setStandardName("内部标准");
        e.setOccurredAt(date);
        e.setPayerName("客户");
        e.setMethod("1");
        e.setMethodText("现金");
        e.setReason("测试存入");
        e.setRequestFingerprint("fixture");
        e.setInputSnapshotJson("{}");
        e.setAmount(new java.math.BigDecimal("100"));
        e.setCashAmount(e.getAmount());
        e.setBeforeBalance(java.math.BigDecimal.ZERO);
        e.setAfterBalance(e.getAmount());
        StandardPersistenceTest.initialize(e);
        fundEntries.insert(e).block(WAIT);
        return e.getId();
    }

    @Configuration
    @Import({
        PrepayQuotePersistenceTest.Config.class,
        PrepayOptionsService.class,
        OwnerPrepayService.class,
        OwnerFinanceService.class
    })
    static class Config {
        @Bean
        OwnerHouseSource ownerHouses() {
            return mock(OwnerHouseSource.class);
        }
    }
}
