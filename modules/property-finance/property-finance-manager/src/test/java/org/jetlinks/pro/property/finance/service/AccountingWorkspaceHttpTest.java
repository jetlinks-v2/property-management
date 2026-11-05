package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.property.finance.calculation.AccountingCalendarRules;
import org.jetlinks.pro.property.finance.web.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.*;

@SpringBootTest(
        classes = AccountingWorkspaceHttpTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AccountingWorkspaceHttpTest extends MonthClosingPersistenceTest {
    @Autowired AccountingPeriodController accountingController;
    @Autowired MonthClosingController closingController;
    @Autowired MonthClosingRecords history;

    WebTestClient client() {
        return WebTestClient.bindToController(accountingController, closingController)
                .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                .webFilter((exchange, chain) -> chain.filter(exchange).contextWrite(allowed()))
                .build();
    }

    @Test
    void realControllerCreatesCalendarClosesFromServerClockAndProvidesReadOnlyHistory() {
        var client = client();
        var root = "/property/finance/projects/" + project;
        client.get()
                .uri(root + "/accounting-periods/2024")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json("[]");
        client.post()
                .uri(root + "/accounting-periods")
                .bodyValue(new AccountingCalendarRules.Input(2024, "NATURAL", 1))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(12);
        var first = calendar.period(project, "2024-01").contextWrite(allowed()).block(WAIT);
        client.get()
                .uri(root + "/accounting-periods/month/2024-01")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.endDate")
                .isEqualTo("2024-01-31");
        var preview =
                client.post()
                        .uri(root + "/month-closing/2024-01/_close/preview")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(MonthClosingService.Preview.class)
                        .returnResult()
                        .getResponseBody();
        var input =
                new MonthClosingService.Submission(
                        UUID.randomUUID().toString(), preview.version(), preview.token(), "HTTP核对");
        var result =
                client.post()
                        .uri(root + "/month-closing/2024-01/_close")
                        .bodyValue(input)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(MonthClosingService.Result.class)
                        .returnResult()
                        .getResponseBody();
        assertEquals("CLOSED", result.status());
        assertEquals(first.getVersion() + 1, result.version());
        client.get()
                .uri(root + "/month-closing/operations/" + result.operationId())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.result.status")
                .isEqualTo("CLOSED")
                .jsonPath("$.operation.requestFingerprint")
                .doesNotExist();
        client.post()
                .uri(root + "/month-closing/2024-01/logs/_query")
                .bodyValue(new QueryParamEntity())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(1);
        client.delete()
                .uri(root + "/accounting-periods/month/2024-01")
                .exchange()
                .expectStatus()
                .isEqualTo(405);
        client.put()
                .uri(root + "/month-closing/2024-01/_close")
                .bodyValue(input)
                .exchange()
                .expectStatus()
                .isEqualTo(405);
        var reopen =
                client.post()
                        .uri(root + "/month-closing/2024-01/_reopen/preview")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(MonthClosingService.Preview.class)
                        .returnResult()
                        .getResponseBody();
        client.post()
                .uri(root + "/month-closing/2024-01/_reopen")
                .bodyValue(
                        new MonthClosingService.Submission(
                                UUID.randomUUID().toString(),
                                reopen.version(),
                                reopen.token(),
                                "HTTP反结"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("OPEN");
        client.post()
                .uri(root + "/month-closing/2024-01/_close")
                .bodyValue(input)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("CLOSED");
    }

    @Test
    void ruleHttpPayloadCannotOverrideClosedThroughOrSkipVersionChecks() {
        january();
        var client = client();
        var path = "/property/finance/projects/" + project + "/month-closing/rule";
        var rule =
                client.get()
                        .uri(path)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(MonthClosingRuleService.View.class)
                        .returnResult()
                        .getResponseBody();
        client.put()
                .uri(path)
                .bodyValue(
                        Map.of(
                                "version",
                                rule.version(),
                                "requireReceivableReview",
                                true,
                                "reason",
                                "HTTP规则",
                                "closedThrough",
                                "2099-12-31"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.requireReceivableReview")
                .isEqualTo(true);
        assertEquals(
                "2024-01-31",
                controls.createQuery()
                        .where("projectId", project)
                        .fetchOne()
                        .block(WAIT)
                        .getClosedThrough());
        client.put()
                .uri(path)
                .bodyValue(
                        Map.of(
                                "version",
                                rule.version(),
                                "requireReceivableReview",
                                false,
                                "reason",
                                "覆盖"))
                .exchange()
                .expectStatus()
                .isEqualTo(409);
    }

    @Test
    void historyForcesProjectAndMonthOutsideForgedClientOrTerms() {
        january();
        var q = new QueryParamEntity();
        q.toQuery().or("projectId", "another-project").or("accountMonth", "2024-02");
        var result = history.query(project, "2024-02", q).contextWrite(allowed()).block(WAIT);
        assertEquals(0, result.getTotal());
        assertTrue(result.getData().isEmpty());
    }

    @Configuration
    @Import({
        MonthClosingPersistenceTest.Config.class,
        AccountingCalendarChangeService.class,
        AccountingCalendarUsage.class,
        MonthClosingRuleService.class,
        MonthClosingRecords.class,
        AccountingPeriodController.class,
        MonthClosingController.class,
        BillingJobProperties.class
    })
    static class Config {}
}
