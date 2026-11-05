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
    void removedBillReviewRoutesCannotChangePublishedBill() {
        var row = bill("room");
        assertFalse(ReceivableView.of(row, false).getAllowedOperations().contains("APPROVE"));
        for (var action : List.of("approve", "reject", "unapprove")) {
            client().post().uri("/property/finance/projects/" + project + "/receivables/_" + action)
                    .bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "items",
                            List.of(Map.of("id", row.getId(), "version", row.getVersion())), "reason", "旧客户端"))
                    .exchange().expectStatus().isEqualTo(410);
        }
        assertEquals(row.getVersion(), bills.findById(row.getId()).block(WAIT).getVersion());
        assertEquals(0, reviewOperations.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Configuration
    @Import({
        ReceivableReviewPersistenceTest.Config.class,
        ReceivableReviewController.class,
        BillingJobProperties.class
    })
    static class Config {}
}
