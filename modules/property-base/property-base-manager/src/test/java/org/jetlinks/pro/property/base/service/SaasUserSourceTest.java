package org.jetlinks.pro.property.base.service;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SaasUserSourceTest {
    @Test
    void snapshotUsesConfiguredSyncBudgetBeyondNinetySeconds() {
        var source = new SaasUserSource() {
            @Override protected Mono<PagerResult<org.jetlinks.sdk.server.auth.UserDetail>> page(
                    QueryParamEntity query) {
                var data = List.of(UserPersistenceTest.user("owner"));
                return Mono.delay(java.time.Duration.ofSeconds(95))
                        .map(ignored -> PagerResult.of(1, data, query));
            }
        };
        org.springframework.test.util.ReflectionTestUtils.setField(source, "timeoutSeconds", 600L);
        StepVerifier.withVirtualTime(source::fetchAll)
                .thenAwait(java.time.Duration.ofSeconds(96))
                .assertNext(values -> assertEquals(java.util.Set.of("owner"), values.keySet()))
                .verifyComplete();
        org.springframework.test.util.ReflectionTestUtils.setField(source, "timeoutSeconds", 90L);
        StepVerifier.withVirtualTime(source::fetchAll)
                .thenAwait(java.time.Duration.ofSeconds(91))
                .expectError(java.util.concurrent.TimeoutException.class).verify();
    }

    @Test
    void directoryPermissionQueryKeepsCallerScopeAndFailsClosed() {
        var marker = new Object();
        var holder = org.mockito.Mockito.mock(org.jetlinks.pro.assets.AssetsHolder.class);
        org.mockito.Mockito.when(holder.filter(org.mockito.ArgumentMatchers.any(reactor.core.publisher.Flux.class),
                org.mockito.ArgumentMatchers.any(org.jetlinks.pro.assets.AssetType.class),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(org.jetlinks.pro.assets.AssetPermission[].class)))
                .thenAnswer(call -> reactor.core.publisher.Flux.deferContextual(ctx -> {
                    assertSame(marker, ctx.get("scope-marker"));
                    assertEquals("user", call.<org.jetlinks.pro.assets.AssetType>getArgument(1).getId());
                    return call.<reactor.core.publisher.Flux<String>>getArgument(0)
                            .filter("allowed"::equals);
                }));
        var source = new SaasUserSource();
        StepVerifier.create(source.authorizedIds(List.of("allowed", "hidden", "allowed"))
                .contextWrite(ctx -> ctx.put("scope-marker", marker)
                        .put(org.jetlinks.pro.assets.AssetsHolder.class, holder)))
                .expectNext("allowed").verifyComplete();
        org.mockito.Mockito.when(holder.filter(org.mockito.ArgumentMatchers.any(reactor.core.publisher.Flux.class),
                org.mockito.ArgumentMatchers.any(org.jetlinks.pro.assets.AssetType.class),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(org.jetlinks.pro.assets.AssetPermission[].class)))
                .thenReturn(reactor.core.publisher.Flux.error(new IllegalStateException("asset unavailable")));
        StepVerifier.create(source.authorizedIds(List.of("allowed"))
                .contextWrite(ctx -> ctx.put(org.jetlinks.pro.assets.AssetsHolder.class, holder)))
                .expectErrorMessage("asset unavailable").verify();
        StepVerifier.create(source.authorizedIds(List.of())).verifyComplete();
    }

    @Test
    void directoryBatchesUniqueCandidatesAndNeverRequestsAnEmptyBatch() {
        var requested = new java.util.ArrayList<String>();
        var batches = new java.util.ArrayList<Integer>();
        var holder = org.mockito.Mockito.mock(org.jetlinks.pro.assets.AssetsHolder.class);
        org.mockito.Mockito.when(holder.filter(org.mockito.ArgumentMatchers.any(reactor.core.publisher.Flux.class),
                org.mockito.ArgumentMatchers.any(org.jetlinks.pro.assets.AssetType.class),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(org.jetlinks.pro.assets.AssetPermission[].class)))
                .thenAnswer(call -> call.<reactor.core.publisher.Flux<String>>getArgument(0)
                        .collectList().flatMapMany(ids -> {
                            assertFalse(ids.isEmpty()); assertTrue(ids.size() <= 200);
                            requested.addAll(ids); batches.add(ids.size());
                            return reactor.core.publisher.Flux.fromIterable(ids);
                        }));
        var source = new SaasUserSource();
        var ids = java.util.stream.IntStream.range(0, 450).mapToObj(i -> "candidate-" + i).toList();
        var duplicates = new java.util.ArrayList<>(ids); duplicates.add(ids.get(0));
        StepVerifier.create(source.authorizedIds(duplicates).collectList()
                .contextWrite(ctx -> ctx.put(org.jetlinks.pro.assets.AssetsHolder.class, holder)))
            .assertNext(result -> assertEquals(ids, result)).verifyComplete();
        assertEquals(ids, requested); assertEquals(java.util.List.of(200, 200, 50), batches);
        StepVerifier.create(source.authorizedIds(java.util.List.of())).verifyComplete();
        assertEquals(3, batches.size());
    }

    @Test
    void rpcMapResponseRestoresNestedUsersAndOrganizations() {
        Object raw =
                java.util.Map.of(
                        "total",
                        1,
                        "pageIndex",
                        0,
                        "pageSize",
                        200,
                        "data",
                        List.of(
                                java.util.Map.of(
                                        "id",
                                        "user-id",
                                        "username",
                                        "account",
                                        "type",
                                        "user",
                                        "status",
                                        1,
                                        "orgList",
                                        List.of(java.util.Map.of("id", "park", "name", "园区")))));
        var response =
                (PagerResult<?>)
                        SaasUserSource.pageCommand(new QueryParamEntity()).createResponseData(raw);
        assertEquals(1, response.getTotal());
        var user =
                assertInstanceOf(
                        org.jetlinks.sdk.server.auth.UserDetail.class, response.getData().get(0));
        assertEquals("user-id", user.getId());
        assertEquals("park", user.getOrgList().get(0).getId());
    }

    @Test
    void stablePagingCollectsEveryIdOnceAndUsesFixedSizeAndSort() {
        var source =
                new SaasUserSource() {
                    @Override
                    protected Mono<PagerResult<org.jetlinks.sdk.server.auth.UserDetail>> page(
                            QueryParamEntity query) {
                        assertEquals(200, query.getPageSize());
                        assertEquals("id", query.getSorts().get(0).getName());
                        var data = new ArrayList<org.jetlinks.sdk.server.auth.UserDetail>();
                        for (int i = query.getPageIndex() * 200;
                                i < Math.min(401, (query.getPageIndex() + 1) * 200);
                                i++) data.add(UserPersistenceTest.user(String.format("%04d", i)));
                        return Mono.just(PagerResult.of(401, data, query));
                    }
                };
        StepVerifier.create(source.fetchAll())
                .assertNext(
                        values -> {
                            assertEquals(401, values.size());
                            assertTrue(values.containsKey("0400"));
                        })
                .verifyComplete();
    }

    @Test
    void duplicatePartialChangingTotalInvalidIdsAndOverLimitAreRejected() {
        for (int mode = 0; mode < 5; mode++) {
            final int scenario = mode;
            var source =
                    new SaasUserSource() {
                        @Override
                        protected Mono<PagerResult<org.jetlinks.sdk.server.auth.UserDetail>> page(
                                QueryParamEntity query) {
                            if (scenario == 4)
                                return Mono.just(PagerResult.of(100001, List.of(), query));
                            var data = new ArrayList<org.jetlinks.sdk.server.auth.UserDetail>();
                            for (int i = 0; i < (scenario == 1 ? 199 : 200); i++)
                                data.add(
                                        UserPersistenceTest.user(
                                                String.format(
                                                        "%04d", i + query.getPageIndex() * 200)));
                            if (scenario == 0) data.set(1, data.get(0));
                            if (scenario == 3) data.get(0).setId(" ");
                            return Mono.just(
                                    PagerResult.of(
                                            query.getPageIndex() > 0 ? 402 : 401, data, query));
                        }
                    };
            StepVerifier.create(source.fetchAll()).expectError().verify();
        }
    }

    @Test
    void exactRecheckDistinguishesAbsenceFromFailure() {
        var source =
                new SaasUserSource() {
                    @Override
                    protected Mono<PagerResult<org.jetlinks.sdk.server.auth.UserDetail>> page(
                            QueryParamEntity query) {
                        assertEquals("id", query.getTerms().get(0).getColumn());
                        return Mono.just(PagerResult.of(0, List.of(), query));
                    }
                };
        StepVerifier.create(source.find("a"))
                .assertNext(value -> assertTrue(value.isEmpty()))
                .verifyComplete();
        var failed =
                new SaasUserSource() {
                    @Override
                    protected Mono<PagerResult<org.jetlinks.sdk.server.auth.UserDetail>> page(
                            QueryParamEntity query) {
                        return Mono.error(new IllegalStateException("upstream denied"));
                    }
                };
        StepVerifier.create(failed.find("a")).expectError(IllegalStateException.class).verify();
    }
}
