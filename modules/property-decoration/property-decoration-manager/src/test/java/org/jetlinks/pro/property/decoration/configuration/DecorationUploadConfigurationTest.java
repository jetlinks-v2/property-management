package org.jetlinks.pro.property.decoration.configuration;

import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.io.file.FileProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class DecorationUploadConfigurationTest {
    private MockServerWebExchange multipart(String filename) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/decoration/attachments")
            .header("content-type", "multipart/form-data; boundary=qa")
            .header("x-tenant-domain", "CQHT")
            .header("x-asset-scope", "business_application:app")
            .body("--qa\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: image/png\r\n\r\nfile-content\r\n--qa--\r\n"));
    }

    @Test void multipartRetainsCaseInsensitiveTenantAndScopeHeadersAndFileBody() {
        WebFilter filter = new DecorationUploadConfiguration().decorationFileUploadWebFilter(new FileProperties());
        StepVerifier.create(filter.filter(multipart("qa.png"), rebuilt -> {
            assertEquals("CQHT", rebuilt.getRequest().getHeaders().getFirst("X-Tenant-Domain"));
            assertEquals("business_application:app", rebuilt.getRequest().getHeaders().getFirst("X-Asset-Scope"));
            return rebuilt.getMultipartData().doOnNext(parts -> {
                assertNotNull(parts.getFirst("file"));
                assertEquals("qa.png", ((org.springframework.http.codec.multipart.FilePart) parts.getFirst("file")).filename());
            }).then();
        })).verifyComplete();
    }

    @Test void ordinaryRequestsKeepHeadersAndReactiveContext() {
        WebFilter filter = new DecorationUploadConfiguration().decorationFileUploadWebFilter(new FileProperties());
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/decoration/configurations")
            .header("x-tenant-domain", "CQHT").body("{}"));
        StepVerifier.create(filter.filter(exchange, rebuilt -> Mono.deferContextual(context -> {
            assertEquals("CQHT", rebuilt.getRequest().getHeaders().getFirst("X-Tenant-Domain"));
            assertEquals("value", context.get("marker"));
            return Mono.empty();
        })).contextWrite(context -> context.put("marker", "value"))).verifyComplete();
    }

    @Test void publicFilePolicyStillRejectsDisallowedFilesBeforeBusinessHandler() {
        FileProperties properties = new FileProperties();
        properties.getPermission().setEnabled(true);
        WebFilter filter = new DecorationUploadConfiguration().decorationFileUploadWebFilter(properties);
        AtomicBoolean reached = new AtomicBoolean();
        StepVerifier.create(filter.filter(multipart("qa.exe"), rebuilt -> {
            reached.set(true);
            return Mono.empty();
        })).expectError(AccessDenyException.class).verify();
        assertFalse(reached.get());
    }
}
