package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.jetlinks.pro.property.finance.activity.*;
import org.jetlinks.pro.property.finance.entity.FinanceCompanyEntity;
import org.jetlinks.pro.property.finance.payment.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.*;

/** 柜台选项仅暴露当前房屋的可信候选，不调用设置和活动维护菜单，不泄露全项目客户和活动范围。 */
@SpringBootTest(
        classes = PrepayOptionsPersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PrepayOptionsPersistenceTest extends PrepayQuotePersistenceTest {
    @Autowired PrepayOptionsService options;
    @Autowired PrepayConfirmationService confirmation;
    @Autowired CashierConfiguration funding;

    @Test
    void onlyCurrentSelectedPayersAndMatchingActivitiesAreExposed() {
        var a = activity(false);
        var other = activity(false);
        var data = ActivityCodec.decode(other.getConfigJson(), ActivitySnapshot.class);
        data.getInput().setTargetIds(List.of("other-root"));
        activities
                .createUpdate()
                .set("configJson", ActivityCodec.encode(data))
                .where("id", other.getId())
                .execute()
                .block(WAIT);
        var source = fixture.context("room");
        var unselected =
                new org.jetlinks.pro.property.base.archive.PropertyHouseCustomer(
                        "unselected",
                        "someone",
                        "user2",
                        "person",
                        "未选客户",
                        null,
                        "owner",
                        1L,
                        1L,
                        1L);
        when(archive.context(eq(fixture.project), eq("room"), anyBoolean()))
                .thenReturn(
                        Mono.just(
                                new org.jetlinks.pro.property.base.archive.PropertyHouseContext(
                                        source.house(),
                                        List.of(source.customers().get(0), unselected))));
        var result = run(options.options(fixture.project, "room", DATE));
        assertEquals(1, result.size());
        var setting = result.get(0);
        assertEquals(settingId, setting.id());
        assertEquals("company", setting.companyId());
        assertEquals("MONTHLY", setting.settlementMethod());
        assertEquals(
                List.of("link"),
                setting.payers().stream().map(PrepayOptionsService.Payer::relationshipId).toList());
        assertEquals(
                List.of(a.getId()),
                setting.activities().stream().map(PrepayOptionsService.Activity::id).toList());
        assertEquals(new ActivityInput.Tier(2, 1), setting.activities().get(0).tiers().get(0));
        verify(archive).context(fixture.project, "room", false);
    }

    @Test
    void stoppedOrUnissuedSettingsAndEndedCustomersAreUnavailable() {
        when(archive.context(eq(fixture.project), eq("room"), anyBoolean()))
                .thenReturn(
                        Mono.just(
                                new org.jetlinks.pro.property.base.archive.PropertyHouseContext(
                                        fixture.context("room").house(), List.of())));
        assertTrue(run(options.options(fixture.project, "room", DATE)).isEmpty());
        when(archive.context(eq(fixture.project), eq("room"), anyBoolean()))
                .thenReturn(Mono.just(fixture.context("room")));
        issued.createUpdate()
                .set("deleted", true)
                .where("id", fixture.relationId)
                .execute()
                .block(WAIT);
        assertTrue(run(options.options(fixture.project, "room", DATE)).isEmpty());
        issued.createUpdate()
                .set("deleted", false)
                .where("id", fixture.relationId)
                .execute()
                .block(WAIT);
        settings.createUpdate()
                .set("status", "STOPPED")
                .where("id", settingId)
                .execute()
                .block(WAIT);
        assertTrue(run(options.options(fixture.project, "room", DATE)).isEmpty());
    }

    @Test
    void consumedOrInactiveActivitiesDisappearButOrdinaryPrepayRemainsAvailable() {
        var a = activity(false);
        var q = create(activityInput(a));
        var company = new FinanceCompanyEntity();
        company.setId("company");
        company.setCompanyName("测试公司");
        company.setVersion(1L);
        when(funding.resolve(eq(fixture.project), any(), eq(true)))
                .thenReturn(
                        Mono.just(
                                new CashierConfiguration.Resolved(
                                        company,
                                        List.of(
                                                new CashierConfiguration.Funding(
                                                        "1", "现金", null, null, null, null, 1L)))));
        run(
                confirmation.confirm(
                        fixture.project,
                        q.id(),
                        new PrepayConfirmationInput(
                                UUID.randomUUID().toString(),
                                0L,
                                "company",
                                "2024-02-01T10:00:00",
                                List.of(
                                        new CashierInput.Tender(
                                                "1", null, new BigDecimal(q.payAmount()))),
                                null,
                                null,
                                null),
                        DATE));
        var b = activity(false);
        activities
                .createUpdate()
                .set("enabled", false)
                .where("id", b.getId())
                .execute()
                .block(WAIT);
        var result = run(options.options(fixture.project, "room", DATE));
        assertEquals(1, result.size());
        assertTrue(result.get(0).activities().isEmpty());
        assertEquals(1, result.get(0).payers().size());
    }

    @Test
    void wrongProjectHouseAndDeniedSpaceCannotLoadOptions() {
        when(archive.context("other", "room", false))
                .thenReturn(Mono.just(fixture.context("room")));
        reject(options.options("other", "room", DATE), 409);
        reject(options.options(fixture.project, "", DATE), 400);
        var holder = mock(org.jetlinks.pro.assets.AssetsHolder.class);
        when(holder.assertPermission(anyString(), anyCollection(), any()))
                .thenReturn(
                        Mono.error(
                                new org.hswebframework.web.exception.BusinessException(
                                        "denied", 403)));
        StepVerifier.create(
                        options.options(fixture.project, "room", DATE)
                                .contextWrite(
                                        fixture.allowed()
                                                .put(
                                                        org.jetlinks.pro.assets.AssetsHolder.class,
                                                        holder)))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
    }

    @Test
    void httpOptionsExposeNoSnapshotPrivateUserFieldsOrActivitySpaceTargets() {
        activity(false);
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                new org.jetlinks.pro.property.finance.web.PrepayOptionsController(
                                        options, new BillingJobProperties()))
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(exchange).contextWrite(fixture.allowed()))
                        .build();
        client.get()
                .uri(
                        "/property/finance/projects/"
                                + fixture.project
                                + "/cashier/prepay/_options?spaceId=room")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].id")
                .isEqualTo(settingId)
                .jsonPath("$[0].payers[0].name")
                .isEqualTo("龚超")
                .jsonPath("$[0].snapshotJson")
                .doesNotExist()
                .jsonPath("$[0].payers[0].userId")
                .doesNotExist()
                .jsonPath("$[0].payers[0].telephone")
                .doesNotExist();
    }

    @Test
    void overLimitSettingsAndActivitiesFailClearlyInsteadOfReturningPartialChoices() {
        var original = settings.findById(settingId).block(WAIT);
        var copies = new ArrayList<org.jetlinks.pro.property.finance.entity.HouseSettingEntity>();
        for (int index = 0; index < 200; index++) {
            var row = new org.jetlinks.pro.property.finance.entity.HouseSettingEntity();
            org.springframework.beans.BeanUtils.copyProperties(original, row);
            row.setId(UUID.randomUUID().toString());
            copies.add(row);
        }
        reactor.core.publisher.Flux.fromIterable(copies)
                .concatMap(settings::insert)
                .then()
                .block(WAIT);
        StepVerifier.create(
                        options.options(fixture.project, "room", DATE)
                                .contextWrite(fixture.allowed()))
                .expectErrorMatches(
                        e ->
                                TaxItemPersistenceTest.status(e, 400)
                                        && e.getMessage().contains("prepay_options_limit"))
                .verify(WAIT);
        settings.createUpdate()
                .set("deleted", true)
                .where("projectId", fixture.project)
                .and("id", "not", settingId)
                .execute()
                .block(WAIT);
        var template = activity(false);
        var many = new ArrayList<org.jetlinks.pro.property.finance.entity.ActivityEntity>();
        for (int index = 0; index < 500; index++) {
            var row = new org.jetlinks.pro.property.finance.entity.ActivityEntity();
            org.springframework.beans.BeanUtils.copyProperties(template, row);
            row.setId(UUID.randomUUID().toString());
            many.add(row);
        }
        reactor.core.publisher.Flux.fromIterable(many)
                .concatMap(activities::insert)
                .then()
                .block(WAIT);
        StepVerifier.create(
                        options.options(fixture.project, "room", DATE)
                                .contextWrite(fixture.allowed()))
                .expectErrorMatches(
                        e ->
                                TaxItemPersistenceTest.status(e, 400)
                                        && e.getMessage().contains("prepay_options_limit"))
                .verify(WAIT);
    }

    @Configuration
    @Import({PrepayConfirmationPersistenceTest.Config.class, PrepayOptionsService.class})
    static class Config {}
}
