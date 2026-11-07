package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.archive.PropertyOwnerHouse;
import org.jetlinks.pro.property.base.command.QueryCurrentPropertyHousesCommand;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.util.List;

/** 跨服务只读本人房屋；不退回手机号、员工空间权限或全量目录。 */
@Component
public class OwnerHouseSource {
    public Mono<List<PropertyOwnerHouse>> rooms(String project, String space) {
        var command = new QueryCurrentPropertyHousesCommand();
        command.setProjectId(project);
        command.setSpaceId(space);
        return CommandSupportManagerProviders.getCommandSupport(
                        QueryCurrentPropertyHousesCommand.SERVICE_ID)
                .switchIfEmpty(Mono.error(error("house_source_unavailable", 503)))
                .flatMap(support -> support.execute(command))
                .switchIfEmpty(Mono.error(error("house_source_unavailable", 503)));
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
