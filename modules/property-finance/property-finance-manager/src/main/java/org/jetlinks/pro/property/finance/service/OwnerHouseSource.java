package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.archive.PropertyOwnerHouse;
import org.jetlinks.pro.property.base.command.QueryCurrentPropertyHousesCommand;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/** 跨服务只读本人房屋，并与当前应用组织读取范围取交集；不退回手机号或全量目录。 */
@Component
@RequiredArgsConstructor
public class OwnerHouseSource {
    private final OwnerApplicationScope applicationScope;

    public Mono<List<PropertyOwnerHouse>> rooms(String project, String space) {
        var command = new QueryCurrentPropertyHousesCommand();
        command.setProjectId(project);
        command.setSpaceId(space);
        return applicationScope
                .current()
                .then(
                        CommandSupportManagerProviders.getCommandSupport(
                                        QueryCurrentPropertyHousesCommand.SERVICE_ID)
                                .switchIfEmpty(Mono.error(error("house_source_unavailable", 503)))
                                .flatMap(support -> support.execute(command))
                                .switchIfEmpty(Mono.error(error("house_source_unavailable", 503))))
                // 本人关联不能代替应用授权：应用只能看到其授权项目内的本人房屋。
                .flatMap(
                        rows ->
                                AssetsHolder.currentReactive()
                                        .switchIfEmpty(Mono.error(error("permission_denied", 403)))
                                        .flatMap(
                                                holder ->
                                                        holder.filter(
                                                                        Flux.fromIterable(rows),
                                                                        AssetType.of(
                                                                                "organization"),
                                                                        row ->
                                                                                row.context()
                                                                                        .house()
                                                                                        .projectId(),
                                                                        false,
                                                                        CrudAssetPermission.read)
                                                                .collectList()));
    }

    public Mono<PropertyOwnerHouse> require(String project, String space) {
        if (project == null || space == null || project.length() > 64 || space.length() > 64)
            return Mono.error(error("owner_room_denied", 403));
        return rooms(project, space)
                .flatMap(
                        rooms ->
                                rooms.stream()
                                        .filter(
                                                room ->
                                                        project.equals(
                                                                        room.context()
                                                                                .house()
                                                                                .projectId())
                                                                && space.equals(
                                                                        room.context()
                                                                                .house()
                                                                                .spaceId()))
                                        .findFirst()
                                        .map(Mono::just)
                                        .orElseGet(
                                                () -> Mono.error(error("owner_room_denied", 403))));
    }
}
