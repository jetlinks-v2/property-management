package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;

import org.jetlinks.pro.property.finance.receivable.ReceivableView;
import org.jetlinks.pro.property.finance.web.ReceivableReviewController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.*;

@SpringBootTest(
        classes = ReceivableReviewHttpTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReceivableReviewHttpTest extends ReceivableReviewPersistenceTest {
    @Autowired ReceivableReviewController controller;

    WebTestClient client() {
        return WebTestClient.bindToController(controller)
                .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                .webFilter((exchange, chain) -> chain.filter(exchange).contextWrite(allowed()))
                .build();
    }

    @Test
    void explicitRouteOwnsActionAndReturnsActualBillIdentitiesAndCapabilities() {
        var row = bill("room");
        assertTrue(ReceivableView.of(row, false).getAllowedOperations().contains("APPROVE"));
        var path = "/property/finance/projects/" + project + "/receivables";
        var body =
                Map.of(
                        "requestId",
                        UUID.randomUUID().toString(),
                        "items",
                        List.of(Map.of("id", row.getId(), "version", row.getVersion())),
                        "reason",
                        "审核按钮",
                        "action",
                        "UNAPPROVE");
        client().post()
                .uri(path + "/_approve")
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].id")
                .isEqualTo(row.getId())
                .jsonPath("$[0].reviewState")
                .isEqualTo("APPROVED");
        client().post()
                .uri(path + "/_approve")
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].reviewState")
                .isEqualTo("APPROVED");
        var approved = bills.findById(row.getId()).block(WAIT);
        assertTrue(ReceivableView.of(approved, false).getAllowedOperations().contains("UNAPPROVE"));
        assertFalse(ReceivableView.of(approved, false).getAllowedOperations().contains("APPROVE"));
        client().post()
                .uri(path + "/_unapprove")
                .bodyValue(
                        Map.of(
                                "requestId",
                                UUID.randomUUID().toString(),
                                "items",
                                List.of(
                                        Map.of(
                                                "id",
                                                row.getId(),
                                                "version",
                                                approved.getVersion())),
                                "reason",
                                "重新核对"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].reviewState")
                .isEqualTo("PENDING");
        var pending = bills.findById(row.getId()).block(WAIT);
        client().post()
                .uri(path + "/_reject")
                .bodyValue(
                        Map.of(
                                "requestId",
                                UUID.randomUUID().toString(),
                                "items",
                                List.of(Map.of("id", row.getId(), "version", pending.getVersion())),
                                "reason",
                                "金额需核对"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].reviewState")
                .isEqualTo("REJECTED");
        client().put()
                .uri(path + "/_approve")
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isEqualTo(405);
    }

    @Test
    void protectedBillCapabilitiesAndSubmitAgreeAndInvalidBatchDoesNotWrite() {
        var row = bill("room");
        bills.createUpdate()
                .set("financialClosed", true)
                .where("id", row.getId())
                .execute()
                .block(WAIT);
        var closed = bills.findById(row.getId()).block(WAIT);
        assertFalse(ReceivableView.of(closed, false).getAllowedOperations().contains("APPROVE"));
        client().post()
                .uri("/property/finance/projects/" + project + "/receivables/_approve")
                .bodyValue(
                        new ReceivableReviewController.Input(
                                UUID.randomUUID().toString(),
                                List.of(
                                        new ReceivableReviewService.Item(
                                                row.getId(), row.getVersion())),
                                "不可审核"))
                .exchange()
                .expectStatus()
                .isEqualTo(409);
        assertEquals(
                0, reviewOperations.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Configuration
    @Import({
        ReceivableReviewPersistenceTest.Config.class,
        ReceivableReviewController.class,
        BillingJobProperties.class
    })
    static class Config {}
}
