package org.jetlinks.pro.property.decoration.web;

import org.jetlinks.pro.property.decoration.entity.DecorationApplicationEntity;
import org.jetlinks.pro.property.decoration.service.DecorationPermitDocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.mockito.Mockito.*;

class DecorationPermitResponseTest {
    @Test
    void privateDocumentUsesThePlatformJsonEnvelope() {
        var documents = mock(DecorationPermitDocumentService.class);
        var row = new DecorationApplicationEntity();
        row.setId("permit-test");
        var document = new DecorationPermitDocumentService.Document(row, "data:image/png;base64,test", "digest", null);
        when(documents.prepare("permit-test")).thenReturn(Mono.just(document));
        var controller = new DecorationApplicationController(null, null, null, null, null, null, null, null, documents);

        WebTestClient.bindToController(controller).build().get()
            .uri("/decoration/applications/permit-test/permit/document")
            .exchange().expectStatus().isOk()
            .expectHeader().valueEquals("Cache-Control", "private, no-store")
            .expectBody().jsonPath("$.status").isEqualTo(200)
            .jsonPath("$.result.application.id").isEqualTo("permit-test")
            .jsonPath("$.result.sealDataUrl").isEqualTo(document.sealDataUrl())
            .jsonPath("$.result.sealDigest").isEqualTo(document.sealDigest());
        verify(documents).prepare("permit-test");
    }
}
