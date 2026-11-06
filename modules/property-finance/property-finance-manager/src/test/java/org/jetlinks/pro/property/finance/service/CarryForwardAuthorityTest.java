package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.hswebframework.web.authorization.*;
import org.hswebframework.web.authorization.simple.*;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.assets.impl.*;
import org.jetlinks.pro.assets.scope.*;
import org.jetlinks.pro.property.finance.entity.CarryForwardPlanEntity;
import org.junit.jupiter.api.*;

import reactor.core.Disposable;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.util.*;

/** 使用真实平台holder/ceiling/scope组合，只替代资产绑定存储和认证查询。 */
class CarryForwardAuthorityTest {
    Disposable provider, materializers;
    final Map<String, Set<String>> rights = new HashMap<>();
    final Map<String, Long> masks = new HashMap<>();

    @BeforeEach
    void holders() {
        rights.put("saved", Set.of("room-a", "room-b"));
        rights.put("current", Set.of("room-a", "room-c"));
        rights.put("token", Set.of("room-a", "room-c"));
        rights.put("application", Set.of("room-a", "room-b", "room-c"));
        AssetBindManager binding = mock(AssetBindManager.class);
        when(binding.getBindings(
                        anyString(), anyCollection(), any(AssetType.class), anyCollection()))
                .thenAnswer(
                        call ->
                                bindings(
                                        call.getArgument(1),
                                        call.getArgument(2),
                                        call.getArgument(3)));
        when(binding.getBindings(anyString(), anyCollection(), any(AssetType.class)))
                .thenAnswer(call -> bindings(call.getArgument(1), call.getArgument(2), null));
        var assets = mock(AssetManager.class);
        provider =
                AssetsHolderHolder.registerProvider(
                        auth ->
                                Mono.just(
                                        new DynamicCustomDimensionAssetsHolder(
                                                assets,
                                                binding,
                                                "space",
                                                type ->
                                                        Flux.fromIterable(
                                                                        auth.getDimensions("test"))
                                                                .filter(
                                                                        dimension ->
                                                                                !AssetScopeActivation
                                                                                        .isScopeOnly(
                                                                                                dimension)))));
        materializers =
                AssetsHolderHolder.registerScopeMaterializers(
                        new AssetScopeMaterializers(
                                List.of(), new DimensionAssetScopeMaterializer(assets, binding)));
    }

    @AfterEach
    void close() {
        provider.dispose();
        materializers.dispose();
    }

    private Flux<AssetBinding> bindings(
            Collection<String> dims, AssetType type, Collection<?> ids) {
        return Flux.fromIterable(dims)
                .flatMap(
                        dimension ->
                                Flux.fromIterable(rights.getOrDefault(dimension, Set.of()))
                                        .filter(id -> ids == null || ids.contains(id))
                                        .map(
                                                id ->
                                                        new SimpleAssetBinding(
                                                                SimpleDimensionType.of("test"),
                                                                dimension,
                                                                type,
                                                                id,
                                                                masks.getOrDefault(
                                                                        dimension,
                                                                        CrudAssetPermission
                                                                                .all_mask),
                                                                null)));
    }

    SimpleAuthentication auth(String dimension) {
        var auth = new SimpleAuthentication();
        var user = new SimpleUser();
        user.setId("user");
        user.setUsername("张三");
        auth.setUser(user);
        auth.setDimensions(
                List.of(
                        SimpleDimension.of(
                                dimension, dimension, SimpleDimensionType.of("test"), Map.of())));
        auth.setPermissions(
                List.of(
                        SimplePermission.builder()
                                .id("property-finance-carry")
                                .actions(Set.of("save"))
                                .build()));
        return auth;
    }

    CarryForwardPlanEntity plan(Authentication auth, Context extra) {
        var plan = new CarryForwardPlanEntity();
        plan.setProjectId("project-a");
        plan.setTenantId("tenant-a");
        plan.setExecutorSnapshot(CarryForwardAuthority.capture(auth).contextWrite(extra).block());
        return plan;
    }

    Context restore(CarryForwardPlanEntity plan, Authentication current) {
        return CarryForwardAuthority.restore(plan, current).block();
    }

    void allow(Context context, String room) {
        StepVerifier.create(
                        AssetsHolder.assertPermission(
                                        AssetType.of("space"), room, CrudAssetPermission.save)
                                .contextWrite(context))
                .verifyComplete();
    }

    void deny(Context context, String room) {
        StepVerifier.create(
                        AssetsHolder.assertPermission(
                                        AssetType.of("space"), room, CrudAssetPermission.save)
                                .contextWrite(context))
                .expectError()
                .verify();
    }

    @Test
    void savedAndCurrentScopesIntersectAfterPersistenceWithoutMutatingCachedAuthentication() {
        var current = auth("current");
        var context = restore(plan(auth("saved"), Context.empty()), current);
        allow(context, "room-a");
        deny(context, "room-b");
        deny(context, "room-c");
        assertTrue(AssetPermissionCeiling.snapshots(current).isEmpty());
        assertEquals(
                "tenant-a", context.get(org.jetlinks.pro.tenant.holder.TenantHolder.CONTEXT_KEY));
    }

    @Test
    void personalTokenCeilingSurvivesSnapshotRoundTrip() {
        var saved = auth("saved");
        AssetPermissionCeiling.inherit(auth("token"), saved);
        var current = auth("saved");
        var context = restore(plan(saved, Context.empty()), current);
        allow(context, "room-a");
        deny(context, "room-b");
        deny(context, "room-c");
    }

    @Test
    void applicationSelectionIsRestoredAndCannotBeReplacedByAmbientRequestContext() {
        var saved = auth("saved");
        var activation =
                AssetScopeActivation.activate(
                        saved,
                        SimpleDimension.of(
                                "application", "应用", SimpleDimensionType.of("test"), Map.of()),
                        Map.of("space", AssetScopeStrategy.SELECT));
        var plan = plan(activation.authentication(), activation.write().apply(Context.empty()));
        rights.put("application", Set.of("room-b"));
        var context = restore(plan, auth("saved"));
        deny(context, "room-a");
        allow(context, "room-b");
        assertFalse(AssetScopeContext.current(context).isEmpty());
    }

    @Test
    void removedUserOrFunctionAndCorruptOrMissingDelegationFailClosed() {
        var plan = plan(auth("saved"), Context.empty());
        var disabled = auth("current");
        disabled.setPermissions(List.of());
        StepVerifier.create(CarryForwardAuthority.restore(plan, disabled)).expectError().verify();
        var other = auth("saved");
        ((SimpleUser) other.getUser()).setId("other");
        StepVerifier.create(CarryForwardAuthority.restore(plan, other)).expectError().verify();
        plan.setExecutorSnapshot("{}");
        StepVerifier.create(CarryForwardAuthority.restore(plan, auth("current")))
                .expectError()
                .verify();
        plan.setExecutorSnapshot(null);
        StepVerifier.create(CarryForwardAuthority.restore(plan, auth("current")))
                .expectError()
                .verify();
    }
}
