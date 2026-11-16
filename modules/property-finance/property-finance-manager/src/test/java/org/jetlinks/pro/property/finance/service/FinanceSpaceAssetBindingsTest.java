package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.simple.*;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.core.event.EventBus;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.assets.impl.DynamicCustomDimensionAssetsHolder;
import org.jetlinks.pro.authorize.OrgDimensionType;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.command.QueryPropertySpaceOrganizationBindingsCommand;
import org.jetlinks.pro.property.base.space.PropertySpaceOrganizationBinding;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;
import reactor.util.context.Context;
import java.util.*;

/** 真实 Composite→绑定提供方→正式命令→Holder 链路；只替代远端传输及存储。 */
class FinanceSpaceAssetBindingsTest {
    @Test
    @SuppressWarnings("unchecked")
    void holderReadsCurrentSourceWithOriginalIdentityAndPermissionBits() {
        var rows = mock(ReactiveRepository.class);
        var provider = new FinanceSpaceAssetBindings(rows);
        var composite = new CompositeAssetBindManager(mock(ApplicationEventPublisher.class), mock(EventBus.class));
        var source = mock(CommandSupport.class);
        var snapshot = new ArrayList<>(List.of(
                new PropertySpaceOrganizationBinding("project-a", "room-a", CrudAssetPermission.all_mask, "owner"),
                new PropertySpaceOrganizationBinding("project-a", "room-b", CrudAssetPermission.read.getMask(), "share")));
        var auth = new SimpleAuthentication();
        var user = new SimpleUser(); user.setId("employee-a"); auth.setUser(user);
        auth.setDimensions(List.of()); auth.setPermissions(List.of());
        when(source.execute(any(QueryPropertySpaceOrganizationBindingsCommand.class))).thenAnswer(call -> {
            var command = (QueryPropertySpaceOrganizationBindingsCommand) call.getArgument(0);
            // 同一命令容器经过 RPC 重建仍保留候选空间与组织；不接受 username 伪造身份。
            var decoded = new QueryPropertySpaceOrganizationBindingsCommand();
            decoded.with("spaceIds", command.getSpaceIds());
            decoded.with("organizationIds", command.getOrganizationIds());
            return Authentication.currentReactive().flatMapMany(current -> {
                assertSame(auth, current);
                assertEquals(List.of("project-a"), decoded.getOrganizationIds());
                return Flux.fromIterable(snapshot)
                        .filter(binding -> decoded.getSpaceIds() == null || decoded.getSpaceIds().contains(binding.spaceId()));
            });
        });
        var previous = AssetBindProvider.supports.get("space");
        var registration = AssetBindProvider.supports.register("space", provider);
        try (var commands = mockStatic(CommandSupportManagerProviders.class)) {
            commands.when(() -> CommandSupportManagerProviders.getCommandSupport(
                    QueryPropertySpaceOrganizationBindingsCommand.SERVICE_ID)).thenReturn(Mono.just(source));
            var holder = new DynamicCustomDimensionAssetsHolder(mock(AssetManager.class), composite, "space",
                    type -> Flux.just(SimpleDimension.of("project-a", "甲", OrgDimensionType.org, Map.of())));
            var candidates = List.of("room-a", "room-b", "outside");
            assertEquals(List.of("room-a", "room-b"), holder.filter(Flux.fromIterable(candidates),
                    AssetType.of("space"), id -> id, CrudAssetPermission.read)
                    .contextWrite(Context.of(Authentication.class, auth)).collectList().block());
            assertEquals(List.of("room-a"), holder.filter(Flux.fromIterable(candidates),
                    AssetType.of("space"), id -> id, CrudAssetPermission.save)
                    .contextWrite(Context.of(Authentication.class, auth)).collectList().block());
            snapshot.remove(0);
            assertEquals(List.of("room-b"), holder.filter(Flux.fromIterable(candidates),
                    AssetType.of("space"), id -> id, CrudAssetPermission.read)
                    .contextWrite(Context.of(Authentication.class, auth)).collectList().block(), "移出项目下次查询立即失权");
            commands.when(() -> CommandSupportManagerProviders.getCommandSupport(
                    QueryPropertySpaceOrganizationBindingsCommand.SERVICE_ID)).thenReturn(Mono.empty());
            StepVerifier.create(holder.filter(Flux.fromIterable(candidates), AssetType.of("space"),
                    id -> id, CrudAssetPermission.read).contextWrite(Context.of(Authentication.class, auth)))
                    .expectError().verify();
            verifyNoInteractions(rows);
        } finally {
            registration.dispose(); previous.ifPresent(value -> AssetBindProvider.supports.register("space", value));
        }
    }
}
