package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.jetlinks.pro.property.finance.web.ContractController;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** 真HTTP来源适配器+真实PG：错误响应不能写入，下载期间发生编辑必须明确冲突。 */
@SpringBootTest(
        classes = ContractSourceHttpTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ContractSourceHttpTest extends ContractMirrorPersistenceTest {
    @Autowired ContractSourceProperties properties;
    @Autowired ContractSourceService remote;
    @Autowired ContractController controller;
    @Autowired ObjectMapper mapper;
    DisposableServer upstream;
    final AtomicReference<String> response = new AtomicReference<>();
    final AtomicReference<String> requestedUri = new AtomicReference<>();

    @BeforeEach
    void sourceSetup() {
        properties.setUrl(null);
    }

    @AfterEach
    void sourceCleanup() {
        if (upstream != null) upstream.disposeNow();
    }

    void server(int status, String body) {
        response.set(body);
        upstream =
                HttpServer.create()
                        .host("127.0.0.1")
                        .port(0)
                        .handle(
                                (request, res) -> {
                                    requestedUri.set(request.uri());
                                    // Windows JDK17默认编码可能是GBK；JSON在真实HTTP中按UTF-8传输。
                                    return res.status(status)
                                            .header("Content-Type", "application/json")
                                            .sendString(
                                                    Mono.fromSupplier(response::get),
                                                    java.nio.charset.StandardCharsets.UTF_8);
                                })
                        .bindNow(WAIT);
        properties.setUrl("http://127.0.0.1:" + upstream.port() + "/contracts");
    }

    Map<String, Object> row(String id, String name) {
        return Map.ofEntries(
                Map.entry("id", id),
                Map.entry("concatNo", "C" + id),
                Map.entry("concatName", name),
                Map.entry("projectId", project),
                Map.entry("houseId", "room"),
                Map.entry("customer", "legacy-customer"),
                Map.entry("startDate", "2024-01-01"),
                Map.entry("endDate", "2024-12-31"),
                Map.entry("leaseArea", "100.00"),
                Map.entry("isDelete", 0));
    }

    String body(Object data) throws Exception {
        return mapper.writeValueAsString(Map.of("code", 0, "data", data));
    }

    @Test
    void sourceDownloadsOldContractFieldsAndUpdatesSameIdentity() throws Exception {
        server(200, body(List.of(row("01", "原名"))));
        var added =
                remote.sync(project, "2024-01-01", "2024-12-31")
                        .contextWrite(permitted)
                        .block(WAIT);
        assertEquals(1, added.added());
        assertTrue(requestedUri.get().contains("projectId=" + project));
        assertTrue(requestedUri.get().contains("startDate=2024-01-01"));
        response.set(body(List.of(row("01", "新名"))));
        assertEquals(
                1,
                remote.sync(project, "2024-01-01", "2024-12-31")
                        .contextWrite(permitted)
                        .block(WAIT)
                        .updated());
        var r =
                mirror.query(project, null, 0, 12)
                        .contextWrite(permitted)
                        .block(WAIT)
                        .getData()
                        .get(0);
        assertEquals("新名", r.name());
        assertEquals("legacy-customer", r.customerName());
        assertEquals("5G园区/1栋/101", r.fullName());
        response.set(body(List.of()));
        assertEquals(
                0,
                remote.sync(project, "2024-01-01", "2024-12-31")
                        .contextWrite(permitted)
                        .block(WAIT)
                        .deleted());
        assertEquals(1, contracts.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void sourceMissingConfigurationBadDatesAndUnauthorizedDoNotContactProvider() {
        rejects(remote.sync(project, "2024-01-01", "2024-12-31"), 503);
        rejects(remote.sync(project, "2024-02-30", "2024-12-31"), 400);
        server(200, "{\"code\":0,\"data\":[]}");
        var auth = assets.auth(reader);
        reactor.test.StepVerifier.create(
                        remote.sync(project, "2024-01-01", "2024-12-31")
                                .contextWrite(
                                        reactor.util.context.Context.of(
                                                org.hswebframework.web.authorization.Authentication
                                                        .class,
                                                auth)))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 403))
                .verify(WAIT);
        assertNull(requestedUri.get());
    }

    @Test
    void sourceMalformedLastRowOrForeignProjectRejectWholeBatch() throws Exception {
        var invalid = new HashMap<>(row("02", "非法"));
        invalid.put("startDate", "2024-02-30");
        server(200, body(List.of(row("01", "可用"), invalid)));
        rejects(remote.sync(project, "2024-01-01", "2024-12-31"), 502);
        invalid.put("startDate", "2024-01-01");
        invalid.put("projectId", "forbidden");
        response.set(body(List.of(row("01", "可用"), invalid)));
        rejects(remote.sync(project, "2024-01-01", "2024-12-31"), 502);
        assertEquals(0, contracts.createQuery().where("projectId", project).count().block(WAIT));
        assertEquals(0, syncStates.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void sourceInvalidEnvelopeLimitAndErrorBodyAreNotSuccessOrLeaked() throws Exception {
        server(200, "{\"code\":1,\"data\":[],\"message\":\"secret-upstream-body\"}");
        rejects(remote.sync(project, "2024-01-01", "2024-12-31"), 502);
        for (String bad :
                List.of(
                        "not-json",
                        "{}",
                        "{\"code\":0}",
                        "{\"code\":0,\"data\":{}}",
                        "{\"code\":4294967296,\"data\":[]}")) {
            response.set(bad);
            rejects(remote.sync(project, "2024-01-01", "2024-12-31"), 502);
        }
        response.set(body(Collections.nCopies(1001, row("01", "超量"))));
        rejects(remote.sync(project, "2024-01-01", "2024-12-31"), 502);
        response.set(
                mapper.writeValueAsString(
                        Map.of(
                                "code",
                                0,
                                "data",
                                List.of(),
                                "padding",
                                "x".repeat(2 * 1024 * 1024 + 1))));
        rejects(remote.sync(project, "2024-01-01", "2024-12-31"), 502);
        assertEquals(0, syncLogs.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void sourceHttpFailureRemainsUnavailableAndMirrorUnchanged() {
        var first = apply(null, item("a", "C01", "原名", false));
        server(503, "{\"message\":\"secret-password\"}");
        reactor.test.StepVerifier.create(
                        remote.sync(project, "2024-01-01", "2024-12-31").contextWrite(permitted))
                .expectErrorMatches(
                        e ->
                                TaxItemPersistenceTest.status(e, 502)
                                        && !e.getMessage().contains("secret"))
                .verify(WAIT);
        assertEquals(first.version(), mirror.version(project).contextWrite(permitted).block(WAIT));
    }

    @Test
    void sourceSlowDownloadCannotOverwriteNewerCommittedBatch() throws Exception {
        var first = apply(null, item("01", "C01", "原名", false));
        var contacted = reactor.core.publisher.Sinks.one();
        var downloaded = reactor.core.publisher.Sinks.<String>one();
        upstream =
                HttpServer.create()
                        .host("127.0.0.1")
                        .port(0)
                        .handle(
                                (request, res) -> {
                                    contacted.tryEmitValue(true);
                                    return res.header("Content-Type", "application/json")
                                            .sendString(
                                                    downloaded.asMono(),
                                                    java.nio.charset.StandardCharsets.UTF_8);
                                })
                        .bindNow(WAIT);
        properties.setUrl("http://127.0.0.1:" + upstream.port() + "/contracts");
        var pending =
                remote.sync(project, "2024-01-01", "2024-12-31").contextWrite(permitted).toFuture();
        contacted.asMono().block(WAIT);
        var latest = apply(first.version(), item("01", "C01", "并发新内容", false));
        downloaded.tryEmitValue(body(List.of(row("01", "下载旧内容"))));
        reactor.test.StepVerifier.create(Mono.fromFuture(pending))
                .expectErrorMatches(e -> TaxItemPersistenceTest.status(e, 409))
                .verify(WAIT);
        assertEquals(latest.version(), mirror.version(project).contextWrite(permitted).block(WAIT));
        assertEquals(
                "并发新内容",
                mirror.query(project, null, 0, 12)
                        .contextWrite(permitted)
                        .block(WAIT)
                        .getData()
                        .get(0)
                        .name());
    }

    @Test
    void sourceExplicitTombstoneRetainsHistoryAndCanRestore() throws Exception {
        server(200, body(List.of(row("01", "原名"))));
        var first =
                remote.sync(project, "2024-01-01", "2024-12-31")
                        .contextWrite(permitted)
                        .block(WAIT);
        var deleted = new HashMap<>(row("01", "原名"));
        deleted.put("isDelete", 1);
        response.set(body(List.of(deleted)));
        assertEquals(
                1,
                remote.sync(project, "2024-01-01", "2024-12-31")
                        .contextWrite(permitted)
                        .block(WAIT)
                        .deleted());
        assertEquals(
                0,
                mirror.query(project, null, 0, 12).contextWrite(permitted).block(WAIT).getTotal());
        assertEquals(1, contracts.createQuery().where("projectId", project).count().block(WAIT));
        response.set(body(List.of(row("01", "恢复"))));
        var restored =
                remote.sync(project, "2024-01-01", "2024-12-31")
                        .contextWrite(permitted)
                        .block(WAIT);
        assertEquals(1, restored.updated());
        assertTrue(restored.version() > first.version());
    }

    @Test
    void sourcePreviouslyUnseenTombstoneReservesIdentityWithoutRequiringActiveSpace()
            throws Exception {
        var tombstone = new HashMap<>(row("01", "上游已删除"));
        tombstone.put("isDelete", 1);
        server(200, body(List.of(tombstone)));
        var result =
                remote.sync(project, "2024-01-01", "2024-12-31")
                        .contextWrite(permitted)
                        .block(WAIT);
        assertEquals(1, result.deleted());
        assertEquals(
                0,
                mirror.query(project, null, 0, 12).contextWrite(permitted).block(WAIT).getTotal());
        var saved = contracts.createQuery().where("projectId", project).fetchOne().block(WAIT);
        assertTrue(saved.getDeleted());
        assertEquals("room", saved.getFullName());
    }

    @Test
    void sourceControllerOffersReadOnlyQueryAndExplicitSync() throws Exception {
        server(200, body(List.of(row("01", "合同"))));
        var client =
                WebTestClient.bindToController(controller)
                        .controllerAdvice(new TaxItemPersistenceTest.HttpErrors())
                        .webFilter(
                                (exchange, chain) -> chain.filter(exchange).contextWrite(permitted))
                        .build();
        String path = "/property/finance/projects/" + project + "/contracts";
        client.post()
                .uri(path + "/_sync")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("startDate", "2024-01-01", "endDate", "2024-12-31"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.added")
                .isEqualTo(1);
        client.post()
                .uri(path + "/_query")
                .bodyValue(Map.of("pageSize", 12, "pageIndex", 0))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.data[0].name")
                .isEqualTo("合同")
                .jsonPath("$.data[0].leaseArea")
                .isEqualTo("100.00")
                .jsonPath("$.data[0].tenantId")
                .doesNotExist();
        client.delete().uri(path + "/any").exchange().expectStatus().isNotFound();
        client.put()
                .uri(path + "/any")
                .bodyValue(Map.of("name", "篡改"))
                .exchange()
                .expectStatus()
                .isNotFound();
        client.post()
                .uri(path + "/_query")
                .bodyValue(Map.of("pageSize", 201))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Configuration
    @Import({
        ContractMirrorPersistenceTest.Config.class,
        ContractSourceProperties.class,
        ContractSourceService.class,
        ContractController.class
    })
    static class Config {
        @Bean
        WebClient.Builder webClientBuilder() {
            return WebClient.builder();
        }
    }
}
