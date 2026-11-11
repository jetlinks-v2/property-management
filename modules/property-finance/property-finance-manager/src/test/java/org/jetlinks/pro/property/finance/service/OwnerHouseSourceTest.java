package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.authorization.simple.*;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.assets.impl.DynamicCustomDimensionAssetsHolder;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.archive.*;
import org.jetlinks.pro.property.base.command.QueryCurrentPropertyHousesCommand;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.util.List;
import java.util.Map;

class OwnerHouseSourceTest {
    final OwnerApplicationScope scope = mock(OwnerApplicationScope.class);
    final OwnerHouseSource source = new OwnerHouseSource(scope);
    final CommandSupport support = mock(CommandSupport.class);

    private PropertyOwnerHouse room(String project, String id) {
        return new PropertyOwnerHouse(
                project,
                new PropertyHouseContext(
                        new PropertyHouseInfo(
                                id,
                                project,
                                id,
                                project + "/" + id,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                0L),
                        List.of()),
                List.of());
    }

    /** 使用真实平台holder，只替代底层绑定存储，证明项目读取权限确实参与过滤。 */
    private Context assets(boolean readable) {
        var binding = mock(AssetBindManager.class);
        when(binding.getBindings(
                        anyString(), anyCollection(), any(AssetType.class), anyCollection()))
                .thenAnswer(
                        call -> {
                            AssetType type = call.getArgument(2);
                            assertEquals("organization", type.getId());
                            if (!readable) return Flux.empty();
                            return Flux.just(
                                    new SimpleAssetBinding(
                                            SimpleDimensionType.of("test"),
                                            "app-role",
                                            type,
                                            "project-a",
                                            CrudAssetPermission.read.getMask(),
                                            null));
                        });
        var holder =
                new DynamicCustomDimensionAssetsHolder(
                        mock(AssetManager.class),
                        binding,
                        "organization",
                        type ->
                                Flux.just(
                                        SimpleDimension.of(
                                                "app-role",
                                                "应用角色",
                                                SimpleDimensionType.of("test"),
                                                Map.of())));
        var user = new SimpleUser();
        user.setId("owner");
        var auth = new SimpleAuthentication();
        auth.setUser(user);
        auth.setDimensions(List.of());
        auth.setPermissions(List.of());
        return Context.of(AssetsHolder.class, holder, Authentication.class, auth);
    }

    @Test
    void ownRoomsAreIntersectedWithCurrentApplicationProjects() {
        when(scope.current()).thenReturn(Mono.just("charge-a"));
        when(support.execute(any(QueryCurrentPropertyHousesCommand.class)))
                .thenReturn(
                        Mono.just(
                                List.of(room("project-a", "room-a"), room("project-b", "room-b"))));
        try (var commands = mockStatic(CommandSupportManagerProviders.class)) {
            commands.when(
                            () ->
                                    CommandSupportManagerProviders.getCommandSupport(
                                            QueryCurrentPropertyHousesCommand.SERVICE_ID))
                    .thenReturn(Mono.just(support));
            StepVerifier.create(source.rooms(null, null).contextWrite(assets(true)))
                    .assertNext(
                            rooms ->
                                    assertEquals(
                                            List.of("room-a"),
                                            rooms.stream()
                                                    .map(r -> r.context().house().spaceId())
                                                    .toList()))
                    .verifyComplete();
            StepVerifier.create(source.require("project-b", "room-b").contextWrite(assets(true)))
                    .expectErrorMatches(
                            e ->
                                    e
                                                    instanceof
                                                    org.hswebframework.web.exception
                                                                            .BusinessException
                                                                    exception
                                            && exception.getStatus() == 403)
                    .verify();
        }
    }

    @Test
    void applicationWithoutProjectGrantCannotReadRooms() {
        when(scope.current()).thenReturn(Mono.just("charge-a"));
        when(support.execute(any(QueryCurrentPropertyHousesCommand.class)))
                .thenReturn(Mono.just(List.of(room("project-a", "room-a"))));
        try (var commands = mockStatic(CommandSupportManagerProviders.class)) {
            commands.when(
                            () ->
                                    CommandSupportManagerProviders.getCommandSupport(
                                            QueryCurrentPropertyHousesCommand.SERVICE_ID))
                    .thenReturn(Mono.just(support));
            StepVerifier.create(source.rooms(null, null).contextWrite(assets(false)))
                    .assertNext(rooms -> assertTrue(rooms.isEmpty()))
                    .verifyComplete();
        }
    }

    @Test
    void deniedApplicationCannotExecuteOwnerHouseCommand() {
        when(scope.current()).thenReturn(Mono.error(new AccessDenyException()));
        try (var commands = mockStatic(CommandSupportManagerProviders.class)) {
            commands.when(
                            () ->
                                    CommandSupportManagerProviders.getCommandSupport(
                                            QueryCurrentPropertyHousesCommand.SERVICE_ID))
                    .thenReturn(Mono.just(support));
            StepVerifier.create(source.rooms(null, null))
                    .expectError(AccessDenyException.class)
                    .verify();
            verifyNoInteractions(support);
        }
    }
}
