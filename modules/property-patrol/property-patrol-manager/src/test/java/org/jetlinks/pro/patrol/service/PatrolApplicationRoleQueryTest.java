package org.jetlinks.pro.patrol.service;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.assets.scope.AssetScope;
import org.jetlinks.pro.assets.scope.AssetScopeContext;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.sdk.server.auth.RoleInfo;
import org.jetlinks.sdk.server.commons.cmd.QueryPagerCommand;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import org.jetlinks.sdk.server.auth.UserInfo;
import org.jetlinks.sdk.server.commons.cmd.QueryListCommand;
import reactor.core.publisher.Flux;
import reactor.util.context.Context;
import static org.mockito.Mockito.mock;

import static org.junit.jupiter.api.Assertions.*;

class PatrolApplicationRoleQueryTest {
    private reactor.core.Disposable materializers;

    @org.junit.jupiter.api.BeforeEach void prepareAssets() {
        materializers = org.jetlinks.pro.assets.AssetsHolderHolder.registerScopeMaterializers(
                new org.jetlinks.pro.assets.scope.AssetScopeMaterializers(List.of(),
                        new org.jetlinks.pro.assets.impl.DimensionAssetScopeMaterializer(
                                mock(org.jetlinks.pro.assets.AssetManager.class),
                                mock(org.jetlinks.pro.assets.AssetBindManager.class))));
    }

    @org.junit.jupiter.api.AfterEach void closeAssets() { materializers.dispose(); }

    private Context callerAssets() {
        var user = new org.hswebframework.web.authorization.simple.SimpleUser(); user.setId("employee");
        var auth = new org.hswebframework.web.authorization.simple.SimpleAuthentication();
        auth.setUser(user); auth.setPermissions(List.of()); auth.setDimensions(List.of());
        return org.jetlinks.pro.assets.scope.AssetScopeActivation.activate(auth,
                org.hswebframework.web.authorization.simple.SimpleDimension.of("patrol-app", "Patrol",
                        org.hswebframework.web.authorization.simple.SimpleDimensionType.of("business_application"), Map.of()),
                Map.of("user", org.jetlinks.pro.assets.scope.AssetScopeStrategy.SELECT)).write().apply(
                        Context.of(org.jetlinks.pro.assets.AssetsHolder.class,
                                new org.jetlinks.pro.assets.impl.CreatorAssetsHolder(user, mock(org.jetlinks.pro.assets.AssetManager.class))));
    }

    private static Flux<UserInfo> memberResponse(QueryListCommand<UserInfo> command) {
        var query = command.asQueryParam();
        assertFalse(query.isPaging());
        assertEquals(java.util.Set.of("id"), query.getIncludes());
        var terms = flatten(query.getTerms()).toList();
        assertTrue(terms.stream().anyMatch(term -> List.of("inspector").equals(term.getValue())
                && term.getTermType().equals("in-dimension")));
        assertTrue(terms.stream().anyMatch(term -> List.of("patrol-app").equals(term.getValue())
                && term.getTermType().equals("in-dimension")));
        assertTrue(terms.stream().anyMatch(term -> "employee".equals(term.getValue())));
        var user = new UserInfo(); user.setId("employee");
        return Flux.just(user, user);
    }

    private static java.util.stream.Stream<org.hswebframework.ezorm.core.param.Term> flatten(
            List<org.hswebframework.ezorm.core.param.Term> terms) {
        return terms.stream().flatMap(term -> java.util.stream.Stream.concat(
                java.util.stream.Stream.of(term), flatten(term.getTerms())));
    }
    @Test
    void directoryPreservesScopeAndProjectsSdkExtensionFields() {
        var marker = new Object();
        var request = QueryParamEntity.newQuery().is("name", "巡更员")
                .or("applicationId", "other-app").getParam();
        request.setPageSize(1000);
        var role = new RoleInfo();
        role.setId("inspector");
        role.setName("巡更员");
        role.setExtension("applicationId", "patrol-app");
        role.setExtension("enabled", true);
        role.setExtension("userIds", List.of("employee"));
        var service = new PatrolRoleService(new org.jetlinks.pro.patrol.integration.PatrolScope()) {
            @Override protected Flux<UserInfo> executeMembers(QueryListCommand<UserInfo> command) {
                return memberResponse(command);
            }
            @Override
            protected Mono<PagerResult<RoleInfo>> execute(QueryPagerCommand<RoleInfo> command) {
                var query = command.asQueryParam();
                assertEquals(200, query.getPageSize());
                // 外部应用条件被整体嵌套，可信应用作为外层 AND，不允许 OR 绕过。
                assertEquals(2, query.getTerms().size());
                assertTrue(query.getTerms().stream().anyMatch(term -> "applicationId".equals(term.getColumn())
                        && "patrol-app".equals(term.getValue()) && term.getType() == org.hswebframework.ezorm.core.param.Term.Type.and));
                assertTrue(query.getTerms().stream().anyMatch(term -> !term.getTerms().isEmpty()));
                return Mono.deferContextual(context -> {
                    assertSame(marker, context.get("caller"));
                    return Mono.just(PagerResult.of(1, List.of(role), query));
                });
            }
        };
        StepVerifier.create(service.queryPager(request)
                        .contextWrite(callerAssets())
                        .contextWrite(context -> context.put("caller", marker)))
                .assertNext(page -> {
                    var result = page.getData().get(0);
                    assertEquals("inspector", result.getId());
                    assertEquals("patrol-app", result.getApplicationId());
                    assertEquals(List.of("employee"), result.getUserIds());
                    assertTrue(result.isEnabled());
                }).verifyComplete();
        assertEquals(1000, request.getPageSize());
    }

    @Test
    void rpcProjectionAcceptsOwnApplicationAndRejectsForeignApplication() {
        for (String application : List.of("patrol-app", "other-app")) {
            var service = new PatrolRoleService(new org.jetlinks.pro.patrol.integration.PatrolScope()) {
                @Override protected Flux<UserInfo> executeMembers(QueryListCommand<UserInfo> command) {
                    return memberResponse(command);
                }
                @Override
                @SuppressWarnings({"rawtypes", "unchecked"})
                protected Mono<PagerResult<RoleInfo>> execute(QueryPagerCommand<RoleInfo> command) {
                    // RPC 扁平 Map 与本地 SDK 扩展对象必须使用相同公开 DTO。
                    return Mono.just((PagerResult) PagerResult.of(1, List.of(Map.of(
                            "id", "inspector", "name", "巡更员", "applicationId", application,
                            "enabled", true, "userIds", List.of("employee"))), command.asQueryParam()));
                }
            };
            var result = StepVerifier.create(service.queryPager(new QueryParamEntity())
                    .contextWrite(callerAssets()));
            if (application.equals("patrol-app")) {
                result.assertNext(page -> assertEquals(List.of("employee"), page.getData().get(0).getUserIds()))
                        .verifyComplete();
            } else result.expectError(AccessDenyException.class).verify();
        }
    }

    @Test void missingMemberListIsReadThroughExistingUserCommandAndFailuresDoNotUseRoleSnapshot() {
        for (boolean deny : List.of(false, true)) {
            var role = new RoleInfo(); role.setId("inspector"); role.setName("巡更员");
            role.setExtension("applicationId", "patrol-app"); role.setExtension("enabled", true);
            role.setExtension("userIds", List.of("unfiltered-remote-member"));
            var service = new PatrolRoleService(new org.jetlinks.pro.patrol.integration.PatrolScope()) {
                @Override protected Mono<PagerResult<RoleInfo>> execute(QueryPagerCommand<RoleInfo> command) {
                    return Mono.just(PagerResult.of(1, List.of(role), command.asQueryParam()));
                }
                @Override protected Flux<UserInfo> executeMembers(QueryListCommand<UserInfo> command) {
                    return deny ? Flux.error(new AccessDenyException.NoStackTrace()) : memberResponse(command);
                }
            };
            var result = StepVerifier.create(service.queryPager(new QueryParamEntity())
                    .contextWrite(callerAssets()));
            if (deny) result.expectError(AccessDenyException.class).verify();
            else result.assertNext(page -> assertEquals(List.of("employee"), page.getData().get(0).getUserIds()))
                    .verifyComplete();
        }
    }

    @Test
    void missingApplicationIsDeniedBeforeCallingAuthenticationService() {
        var service = new PatrolRoleService(new org.jetlinks.pro.patrol.integration.PatrolScope()) {
            @Override
            protected Mono<PagerResult<RoleInfo>> execute(QueryPagerCommand<RoleInfo> command) {
                fail("must not query roles without application scope");
                return Mono.empty();
            }
        };
        StepVerifier.create(service.queryPager(new QueryParamEntity()))
                .expectError(AccessDenyException.class).verify();
    }

    private static AssetScopeContext applicationScope() {
        return AssetScopeContext.of(AssetScope.of(BusinessApplicationDimension.TYPE, "patrol-app"));
    }
}
