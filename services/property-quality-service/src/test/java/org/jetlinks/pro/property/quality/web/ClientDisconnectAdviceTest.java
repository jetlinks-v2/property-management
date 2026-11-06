package org.jetlinks.pro.property.quality.web;

import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.crud.web.CommonErrorControllerAdvice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.buffer.DataBuffer;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.channel.AbortedException;
import reactor.test.StepVerifier;

import java.nio.channels.ClosedChannelException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientDisconnectAdviceTest {

    private final AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(TestConfiguration.class);

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    void committedWriteFailureDoesNotWriteAnotherErrorResponse() {
        var response = new MockServerHttpResponse();
        StepVerifier.create(WebHttpHandlerBuilder.applicationContext(context).build()
            .handle(MockServerHttpRequest.get("/write-disconnect").build(), response))
            .verifyComplete();
        assertEquals(1, context.getBean(AtomicInteger.class).get());
    }

    @Test
    void uncommittedUpstreamDisconnectStillUsesFrameworkErrorProtocol() {
        client().get().uri("/upstream-disconnect").exchange()
            .expectStatus().is5xxServerError()
            .expectBody().jsonPath("$.status").isEqualTo(500);
    }

    @Test
    void normalResponseIsUnchanged() {
        client().get().uri("/normal").exchange().expectStatus().isOk()
            .expectBody(String.class).isEqualTo("ok");
    }

    @Test
    void businessPermissionFailureIsNotSuppressed() {
        client().get().uri("/denied").exchange()
            .expectBody().jsonPath("$.status").isEqualTo(403);
    }

    private WebTestClient client() {
        return WebTestClient.bindToApplicationContext(context).build();
    }

    @Configuration
    @EnableWebFlux
    @Import({ClientDisconnectAdvice.class, CommonErrorControllerAdvice.class, TestController.class})
    static class TestConfiguration {
        @Bean
        AtomicInteger responseWrites() {
            return new AtomicInteger();
        }

        @Bean
        WebFilter disconnectedResponse(AtomicInteger responseWrites) {
            return (exchange, chain) -> {
                if (!exchange.getRequest().getPath().value().equals("/write-disconnect")) {
                    return chain.filter(exchange);
                }
                ServerHttpResponse response = new ServerHttpResponseDecorator(exchange.getResponse()) {
                    @Override
                    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                        responseWrites.incrementAndGet();
                        return super.writeWith(body)
                            .then(Mono.error(new AbortedException(new ClosedChannelException())));
                    }
                };
                return chain.filter(exchange.mutate().response(response).build());
            };
        }
    }

    @RestController
    static class TestController {
        @GetMapping({"/normal", "/write-disconnect"})
        Mono<String> normal() {
            return Mono.just("ok");
        }

        @GetMapping("/upstream-disconnect")
        Mono<String> upstreamDisconnect() {
            return Mono.error(new AbortedException(new ClosedChannelException()));
        }

        @GetMapping("/denied")
        Mono<String> denied() {
            return Mono.error(new AccessDenyException());
        }
    }
}
