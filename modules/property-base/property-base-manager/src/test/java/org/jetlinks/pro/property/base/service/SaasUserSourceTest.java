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
    void snapshotRecheckAndAuthorizedQueriesUseTheSameConfiguredUserNode() {
        var source = new SaasUserSource();
        org.springframework.test.util.ReflectionTestUtils.setField(source, "serverId", "runtime-users");
        var support = org.mockito.Mockito.mock(org.jetlinks.core.command.CommandSupport.class);
        org.mockito.Mockito.when(support.execute(org.mockito.ArgumentMatchers.any(
                org.jetlinks.sdk.server.commons.cmd.QueryPagerCommand.class)))
            .thenAnswer(call -> Mono.deferContextual(context -> {
                if (call.getArgument(0) instanceof org.jetlinks.sdk.server.auth.cmd.QueryUserDetailPageCommand) {
                    assertFalse(context.hasKey("scope-marker"));
                    return Mono.just(PagerResult.of(1,
                        List.of(UserPersistenceTest.user("owner")), new QueryParamEntity()));
                }
                assertEquals("caller-scope", context.get("scope-marker"));
                return Mono.just(PagerResult.of(1, List.of(info("owner")), new QueryParamEntity()));
            }));
        var routes = new java.util.concurrent.atomic.AtomicInteger();
        var providers = org.jetlinks.pro.command.CommandSupportManagerProvider.supports;
        var previous = providers.get("authService:user");
        var registration = providers.register("authService:user", new org.jetlinks.pro.command.CommandSupportManagerProvider() {
            public String getProvider() { return "authService:user"; }
            public Mono<? extends org.jetlinks.core.command.CommandSupport> getCommandSupport(
                    String module, java.util.Map<String, Object> options) {
                return Mono.deferContextual(context -> {
                    assertTrue(context.stream().anyMatch(entry -> "runtime-users".equals(entry.getValue())));
                    routes.incrementAndGet();
                    return Mono.just(support);
                });
            }
        });
        try {
            StepVerifier.create(source.fetchAll()).assertNext(rows -> assertTrue(rows.containsKey("owner"))).verifyComplete();
            StepVerifier.create(source.find("owner")).assertNext(found -> assertTrue(found.isPresent())).verifyComplete();
            StepVerifier.create(source.authorizedPage(new QueryParamEntity())
                .contextWrite(context -> context.put("scope-marker", "caller-scope")))
                .assertNext(page -> assertEquals("owner", page.getData().get(0).getId())).verifyComplete();
            assertEquals(3, routes.get());
        } finally {
            registration.dispose();
            previous.ifPresent(provider -> providers.register("authService:user", provider));
        }
    }

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
    void directoryQueriesCloudWithRealAssetConditionsAndKeepsCallerScope() {
        var marker = new Object();
        var assets = org.mockito.Mockito.mock(org.jetlinks.pro.assets.AssetManager.class);
        var user = new org.hswebframework.web.authorization.simple.SimpleUser(); user.setId("reader");
        var holder = new org.jetlinks.pro.assets.impl.CreatorAssetsHolder(user, assets);
        var source = new SaasUserSource() {
            @Override protected Mono<PagerResult<org.jetlinks.sdk.server.auth.UserInfo>> authorizedPage(QueryParamEntity query) {
                return Mono.deferContextual(ctx -> {
                    assertSame(marker, ctx.get("scope-marker"));
                    var terms = flatten(query.getTerms());
                    assertTrue(terms.stream().anyMatch(t ->
                        org.jetlinks.pro.assets.impl.terms.AssetsCreatorTermFragmentBuilder.createTermType(
                            org.jetlinks.pro.assets.AssetType.of("user")).equals(t.getTermType())
                            && "reader".equals(t.getValue())));
                    assertTrue(terms.stream().anyMatch(t -> "id".equals(t.getColumn())
                            && t.getValue() instanceof List<?> ids && ids.equals(List.of("allowed", "hidden"))));
                    return Mono.just(PagerResult.of(1, List.of(info("allowed")), query));
                });
            }
        };
        StepVerifier.create(source.authorizedIds(List.of("allowed", "hidden", "allowed"))
                .contextWrite(ctx -> ctx.put("scope-marker", marker)
                        .put(org.jetlinks.pro.assets.AssetsHolder.class, holder)))
                .expectNext("allowed").verifyComplete();
        org.mockito.Mockito.verifyNoInteractions(assets);
        var failed = new SaasUserSource() {
            @Override protected Mono<PagerResult<org.jetlinks.sdk.server.auth.UserInfo>> authorizedPage(QueryParamEntity query) {
                return Mono.error(new IllegalStateException("upstream denied"));
            }
        };
        StepVerifier.create(failed.authorizedIds(List.of("allowed")))
                .expectErrorMessage("upstream denied").verify();
    }

    @Test
    void directoryBatchesUniqueCandidatesAndRejectsUnrequestedCloudIds() {
        var requested = new java.util.ArrayList<String>();
        var batches = new java.util.ArrayList<Integer>();
        var source = new SaasUserSource() {
            @Override protected Mono<PagerResult<org.jetlinks.sdk.server.auth.UserInfo>> authorizedPage(QueryParamEntity query) {
                @SuppressWarnings("unchecked") var ids = (List<String>)query.getTerms().get(0).getValue();
                assertFalse(ids.isEmpty()); assertTrue(ids.size() <= 200);
                requested.addAll(ids); batches.add(ids.size());
                List<org.jetlinks.sdk.server.auth.UserInfo> rows = ids.stream()
                    .map(id -> info(id)).toList();
                return Mono.just(PagerResult.of(rows.size(), rows, query));
            }
        };
        var ids = java.util.stream.IntStream.range(0, 450).mapToObj(i -> "candidate-" + i).toList();
        var duplicates = new java.util.ArrayList<>(ids); duplicates.add(ids.get(0));
        StepVerifier.create(source.authorizedIds(duplicates).collectList())
            .assertNext(result -> assertEquals(ids, result)).verifyComplete();
        assertEquals(ids, requested); assertEquals(java.util.List.of(200, 200, 50), batches);
        StepVerifier.create(source.authorizedIds(java.util.List.of())).verifyComplete();
        assertEquals(3, batches.size());
        var invalid = new SaasUserSource() {
            @Override protected Mono<PagerResult<org.jetlinks.sdk.server.auth.UserInfo>> authorizedPage(QueryParamEntity query) {
                return Mono.just(PagerResult.of(1, List.of(info("unrequested")), query));
            }
        };
        StepVerifier.create(invalid.authorizedIds(List.of("allowed")))
                .expectError(org.hswebframework.web.exception.BusinessException.class).verify();
    }

    private static List<org.hswebframework.ezorm.core.param.Term> flatten(List<org.hswebframework.ezorm.core.param.Term> terms) {
        var result = new ArrayList<org.hswebframework.ezorm.core.param.Term>();
        for (var term : terms) { result.add(term); result.addAll(flatten(term.getTerms())); }
        return result;
    }

    private static org.jetlinks.sdk.server.auth.UserInfo info(String id) {
        var user = new org.jetlinks.sdk.server.auth.UserInfo(); user.setId(id); return user;
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
