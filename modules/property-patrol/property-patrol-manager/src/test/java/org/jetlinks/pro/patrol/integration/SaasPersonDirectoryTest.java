package org.jetlinks.pro.patrol.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
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
import org.jetlinks.sdk.server.auth.cmd.QueryUserDetailPageCommand;
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
    private final SaasPersonDirectory directory =
            new SaasPersonDirectory(new PatrolScope(), () -> Mono.just(support));

    private Context identity() {
        var user = new SimpleUser();
        user.setId("operator");
        var auth = new SimpleAuthentication();
        auth.setUser(user);
        auth.setPermissions(List.of());
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
                        TenantHolder.makeCurrent("north")
                                .put(
                                        AssetsHolder.class,
                                        new CreatorAssetsHolder(user, mock(AssetManager.class))));
    }

    @Test
    void userQueryKeepsMandatoryScopeAndLimitsWithoutMutatingClientQuery() {
        when(support.execute(any(QueryUserDetailPageCommand.class)))
                .thenReturn(Mono.just(PagerResult.empty()));
        var request =
                QueryParamEntity.newQuery().where("name", "Alice").or("name", "Bob").getParam();
        request.setPageSize(1000);
        int original = request.getTerms().size();
        StepVerifier.create(directory.query(request).contextWrite(identity()))
                .expectNextCount(1)
                .verifyComplete();
        var captor = ArgumentCaptor.forClass(QueryUserDetailPageCommand.class);
        verify(support).execute(captor.capture());
        var query = captor.getValue().asQueryParam();
        assertEquals(200, query.getPageSize());
        assertEquals(1000, request.getPageSize());
        assertEquals(original, request.getTerms().size());
        var terms = flatten(query.getTerms()).toList();
        assertTrue(
                terms.stream()
                        .anyMatch(
                                term ->
                                        "tenantId".equals(term.getColumn())
                                                && "north".equals(term.getValue())));
        assertTrue(
                terms.stream()
                        .anyMatch(
                                term ->
                                        "status".equals(term.getColumn())
                                                && Integer.valueOf(1).equals(term.getValue())));
        assertTrue(
                terms.stream()
                        .anyMatch(
                                term ->
                                        "id".equals(term.getColumn())
                                                && "in-dimension".equals(term.getTermType())
                                                && List.of("app-a").equals(term.getValue())));
        assertTrue(
                terms.stream()
                        .anyMatch(
                                term ->
                                        "id".equals(term.getColumn())
                                                && "operator".equals(term.getValue())));
        // 客户端 OR 条件必须被嵌套，不能与强制范围条件成为同级 OR。
        assertTrue(query.getTerms().stream().noneMatch(term -> term.getType() == Term.Type.or));
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
    void unavailableMemberCannotPassExecutionOrAssignmentValidation() {
        when(support.execute(any(QueryUserDetailPageCommand.class)))
                .thenReturn(Mono.just(PagerResult.empty()));
        StepVerifier.create(
                        directory.assertMembers(List.of("removed-user")).contextWrite(identity()))
                .expectError()
                .verify();
    }

    private Stream<Term> flatten(List<Term> terms) {
        return terms.stream()
                .flatMap(term -> Stream.concat(Stream.of(term), flatten(term.getTerms())));
    }
}
