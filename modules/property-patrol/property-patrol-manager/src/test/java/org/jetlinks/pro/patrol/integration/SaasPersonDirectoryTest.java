package org.jetlinks.pro.patrol.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleDimension;
import org.hswebframework.web.authorization.simple.SimpleDimensionType;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.assets.AssetManager;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.impl.CreatorAssetsHolder;
import org.jetlinks.pro.assets.scope.AssetScopeActivation;
import org.jetlinks.pro.assets.scope.AssetScopeContext;
import org.jetlinks.pro.assets.scope.AssetScopeStrategy;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.jetlinks.pro.property.base.command.QueryPropertyEmployeePageCommand;
import org.jetlinks.pro.property.base.command.GetCurrentPropertyEmployeeCommand;
import org.jetlinks.pro.property.base.user.PropertyEmployeeInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

class SaasPersonDirectoryTest {
    private reactor.core.Disposable materializers;

    @org.junit.jupiter.api.BeforeEach
    void setupScopes() {
        materializers =
                org.jetlinks.pro.assets.AssetsHolderHolder.registerScopeMaterializers(
                        new org.jetlinks.pro.assets.scope.AssetScopeMaterializers(
                                List.of(),
                                new org.jetlinks.pro.assets.impl.DimensionAssetScopeMaterializer(
                                        mock(AssetManager.class),
                                        mock(org.jetlinks.pro.assets.AssetBindManager.class))));
    }

    @org.junit.jupiter.api.AfterEach
    void closeScopes() {
        materializers.dispose();
    }

    private final CommandSupport support = mock(CommandSupport.class);
    private final CommandSupport accounts = mock(CommandSupport.class);

    @org.junit.jupiter.api.BeforeEach
    void applicationMembers() {
        var user = new org.jetlinks.sdk.server.auth.UserInfo(); user.setId("operator");
        doReturn(reactor.core.publisher.Flux.just(user)).when(accounts)
                .execute(any(org.jetlinks.sdk.server.commons.cmd.QueryListCommand.class));
    }

    private void assertApplicationQuery() {
        var captor = ArgumentCaptor.forClass(org.jetlinks.sdk.server.commons.cmd.QueryListCommand.class);
        verify(accounts).execute(captor.capture());
        var terms = flatten(captor.getValue().asQueryParam().getTerms()).toList();
        assertTrue(terms.stream().anyMatch(term -> "in-dimension".equals(term.getTermType())
                && List.of("app-a").equals(term.getValue())));
        assertTrue(terms.stream().anyMatch(term -> "operator".equals(term.getValue())));
    }
    private final SaasPersonDirectory directory =
            new SaasPersonDirectory(new PatrolScope(), () -> Mono.just(support), () -> Mono.just(accounts));

    private Context identity() { return identity(true); }

    private Context identity(boolean withTenant) {
        return identity(withTenant, false);
    }

    private Context identity(boolean withTenant, boolean userQuery) {
        var user = new SimpleUser();
        user.setId("operator");
        var auth = new SimpleAuthentication();
        auth.setUser(user);
        auth.setPermissions(userQuery ? List.of(org.hswebframework.web.authorization.simple.SimplePermission.builder()
                .id("user").actions(java.util.Set.of("query")).build()) : List.of());
        auth.setDimensions(List.of());
        return AssetScopeActivation.activate(
                        auth,
                        SimpleDimension.of(
                                "app-a",
                                "Patrol",
                                SimpleDimensionType.of("business_application"),
                                Map.of()),
                        Map.of("user", AssetScopeStrategy.SELECT))
                .write()
                .apply(
                        (withTenant ? TenantHolder.makeCurrent("north") : Context.empty())
                                .put(PatrolProjectContext.class, ProjectTestContexts.project(withTenant ? "north|app-a" : "|app-a"))
                                .put(
                                        AssetsHolder.class,
                                        new CreatorAssetsHolder(user, mock(AssetManager.class))));
    }

    @Test
    void userQueryKeepsMandatoryScopeAndLimitsWithoutMutatingClientQuery() {
        when(support.execute(any(QueryPropertyEmployeePageCommand.class)))
                .thenReturn(Mono.just(PagerResult.empty()));
        var request =
                QueryParamEntity.newQuery().where("name", "Alice").or("name", "Bob").getParam();
        request.setPageSize(1000);
        int original = request.getTerms().size();
        StepVerifier.create(directory.query(request).contextWrite(identity()))
                .expectNextCount(1)
                .verifyComplete();
        var captor = ArgumentCaptor.forClass(QueryPropertyEmployeePageCommand.class);
        verify(support).execute(captor.capture());
        var query = captor.getValue().getQuery();
        assertEquals(200, query.getPageSize());
        assertEquals(1000, request.getPageSize());
        assertEquals(original, request.getTerms().size());
        var terms = flatten(query.getTerms()).toList();
        // 应用成员由 SaaS 管理，巡更不把技术租户当成园区项目再次筛选人员。
        assertTrue(terms.stream().noneMatch(term -> "tenantId".equals(term.getColumn())));
        assertTrue(
                terms.stream()
                        .anyMatch(
                                term ->
                                        "status".equals(term.getColumn())
                                                && Integer.valueOf(1).equals(term.getValue())));
        assertApplicationQuery();
        assertTrue(terms.stream().noneMatch(term -> "in-dimension".equals(term.getTermType())));
        assertTrue(terms.stream().anyMatch(term -> "in".equals(term.getTermType())
                && List.of("operator").equals(term.getValue())));
        assertTrue(
                terms.stream()
                        .anyMatch(
                                term ->
                                        "id".equals(term.getColumn())
                                                && List.of("operator").equals(term.getValue())));
        // 客户端 OR 条件必须被嵌套，不能与强制范围条件成为同级 OR。
        assertTrue(query.getTerms().stream().noneMatch(term -> term.getType() == Term.Type.or));
    }

    @Test
    void platformApplicationWithoutTenantStillUsesApplicationAndAssetBoundaries() {
        when(support.execute(any(QueryPropertyEmployeePageCommand.class)))
                .thenReturn(Mono.just(PagerResult.empty()));
        StepVerifier.create(new PatrolScope().current().contextWrite(identity(false)))
                .assertNext(current -> {
                    assertEquals("", current.tenantId());
                    assertEquals("app-a", current.applicationId());
                    assertEquals("|app-a", current.key());
                }).verifyComplete();
        StepVerifier.create(directory.query(new QueryParamEntity()).contextWrite(identity(false)))
                .expectNextCount(1).verifyComplete();
        var captor = ArgumentCaptor.forClass(QueryPropertyEmployeePageCommand.class);
        verify(support).execute(captor.capture());
        var terms = flatten(captor.getValue().getQuery().getTerms()).toList();
        assertApplicationQuery();
        assertTrue(terms.stream().noneMatch(term -> "in-dimension".equals(term.getTermType())));
        assertTrue(terms.stream().anyMatch(term -> "id".equals(term.getColumn())
                && List.of("operator").equals(term.getValue())));
    }

    @Test
    void rpcMapElementsAreConvertedWithoutCastingBeforeTheAdapter() {
        doReturn(Mono.just(PagerResult.of(1, List.of(Map.of("id", "operator", "name", "测试巡更员")))))
                .when(support).execute(any(QueryPropertyEmployeePageCommand.class));
        StepVerifier.create(directory.query(new QueryParamEntity()).contextWrite(identity(false)))
                .assertNext(page -> {
                    assertEquals(1, page.getTotal());
                    assertEquals(new SaasPersonDirectory.Person("operator", "测试巡更员"), page.getData().get(0));
                }).verifyComplete();
    }

    @Test
    void missingApplicationIsRejectedBeforeCallingRemoteService() {
        StepVerifier.create(
                        directory
                                .query(new QueryParamEntity())
                                .contextWrite(AssetScopeContext.replace(AssetScopeContext.empty()))
                                .contextWrite(identity()))
                .expectError()
                .verify();
        verifyNoInteractions(support);
    }

    @Test
    void ownMembershipUsesTrustedSaasDimensionWithoutUserDirectoryPermission() {
        when(support.execute(any(GetCurrentPropertyEmployeeCommand.class))).thenReturn(Mono.just(
                new PropertyEmployeeInfo("operator", "Operator", null, List.of("project"))));
        var user = new SimpleUser(); user.setId("operator");
        var auth = new SimpleAuthentication(); auth.setUser(user); auth.setPermissions(List.of());
        auth.setDimensions(List.of(SimpleDimension.of("app-a", "Patrol",
                SimpleDimensionType.of("business_application"), Map.of())));
        StepVerifier.create(directory.assertMembers(List.of("operator"))
                .contextWrite(identity(false).put(org.hswebframework.web.authorization.Authentication.class, auth)))
                .verifyComplete();
        verify(support).execute(any(GetCurrentPropertyEmployeeCommand.class));
    }

    @Test
    void selectedApplicationDoesNotMakeNonMemberEligible() {
        // 只有激活的应用上下文、没有认证成员维度时必须拒绝；其他应用成员也不能冒用当前入口。
        for (String memberApp : List.of("", "app-b")) {
            var user = new SimpleUser(); user.setId("operator");
            var auth = new SimpleAuthentication(); auth.setUser(user); auth.setPermissions(List.of());
            auth.setDimensions(memberApp.isEmpty() ? List.of() : List.of(SimpleDimension.of(memberApp,
                    "Other", SimpleDimensionType.of("business_application"), Map.of())));
            StepVerifier.create(directory.assertMembers(List.of("operator"))
                    .contextWrite(identity(false).put(org.hswebframework.web.authorization.Authentication.class, auth)))
                    .expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
        }
        verifyNoInteractions(support);
    }

    @Test
    void unavailableMemberCannotPassExecutionOrAssignmentValidation() {
        when(support.execute(any(QueryPropertyEmployeePageCommand.class)))
                .thenReturn(Mono.just(PagerResult.empty()));
        StepVerifier.create(
                        directory.assertMembers(List.of("removed-user")).contextWrite(identity()))
                .expectError()
                .verify();
    }

    @Test
    void calendarContactsStayEmptyWithoutUserQueryPermission() {
        StepVerifier.create(directory.telephones(List.of("operator")).contextWrite(identity(false)))
                .assertNext(phones -> assertTrue(phones.isEmpty())).verifyComplete();
        verifyNoInteractions(support);
    }

    @Test
    void calendarContactsUseTheOriginalApplicationAndUserAssetQuery() {
        doReturn(Mono.just(PagerResult.of(1, List.of(Map.of("id", "operator", "name", "巡更员",
                "telephone", "13800000001")))))
                .when(support).execute(any(QueryPropertyEmployeePageCommand.class));
        StepVerifier.create(directory.telephones(List.of("operator", "unreadable-user", "operator"))
                .contextWrite(identity(false, true)))
                .expectNext(Map.of("operator", "13800000001")).verifyComplete();
        var captor = ArgumentCaptor.forClass(QueryPropertyEmployeePageCommand.class);
        verify(support).execute(captor.capture());
        var query = captor.getValue().getQuery();
        var terms = flatten(query.getTerms()).toList();
        assertEquals(200, query.getPageSize());
        assertTrue(terms.stream().anyMatch(term -> "id".equals(term.getColumn())
                && List.of("operator", "unreadable-user").equals(term.getValue())));
        assertApplicationQuery();
        assertTrue(terms.stream().noneMatch(term -> "in-dimension".equals(term.getTermType())));
        assertTrue(terms.stream().anyMatch(term -> "id".equals(term.getColumn())
                && List.of("operator").equals(term.getValue())));
    }

    @Test
    void applicationMemberWithoutActiveEmployeeIdentityCannotExecute() {
        when(support.execute(any(GetCurrentPropertyEmployeeCommand.class))).thenReturn(Mono.empty());
        var user = new SimpleUser(); user.setId("operator");
        var auth = new SimpleAuthentication(); auth.setUser(user); auth.setPermissions(List.of());
        auth.setDimensions(List.of(SimpleDimension.of("app-a", "Patrol", SimpleDimensionType.of("business_application"), Map.of())));
        StepVerifier.create(directory.assertMembers(List.of("operator"))
                .contextWrite(identity(false).put(org.hswebframework.web.authorization.Authentication.class, auth)))
                .expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
    }

    private Stream<Term> flatten(List<Term> terms) {
        return terms.stream()
                .flatMap(term -> Stream.concat(Stream.of(term), flatten(term.getTerms())));
    }
}
