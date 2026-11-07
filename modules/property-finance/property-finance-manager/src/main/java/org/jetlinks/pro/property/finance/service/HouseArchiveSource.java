package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.archive.*;
import org.jetlinks.pro.property.base.command.*;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/** 基础资料只通过公开core命令访问，错误保持原义；不以空列表掩盖同步或权限故障。 */
@Component
public class HouseArchiveSource {
    /** 显式命令沿用订阅者认证与资产上下文；远程不可用不能降级成零资产。 */
    public Mono<PropertyHouseSummary> summary(java.util.List<String> projectIds) {
        return Mono.defer(
                () -> {
                    var command = new QueryPropertyHouseSummaryCommand();
                    command.setProjectIds(projectIds);
                    return CommandSupportManagerProviders.getCommandSupport(command.SERVICE_ID)
                            .switchIfEmpty(Mono.error(error("house_source_unavailable", 503)))
                            .flatMap(support -> support.execute(command))
                            .switchIfEmpty(Mono.error(error("house_source_unavailable", 503)));
                });
    }

    public Mono<PropertyHouseContext> context(String projectId, String spaceId, boolean forUpdate) {
        return Mono.defer(
                () -> {
                    var command = new QueryPropertyHouseContextCommand();
                    command.setProjectId(projectId);
                    command.setSpaceId(spaceId);
                    command.setForUpdate(forUpdate);
                    return CommandSupportManagerProviders.getCommandSupport(command.SERVICE_ID)
                            .switchIfEmpty(Mono.error(error("house_source_unavailable", 503)))
                            .flatMap(support -> support.execute(command))
                            .switchIfEmpty(Mono.error(error("house_source_unavailable", 503)));
                });
    }

    public Mono<PropertyHousePage> houses(
            String projectId, String spaceId, String keyword, int pageIndex, int pageSize) {
        return Mono.defer(
                () -> {
                    var command = new QueryPropertyHousesCommand();
                    command.setProjectId(projectId);
                    command.setSpaceId(spaceId);
                    command.setKeyword(keyword);
                    command.setPageIndex(pageIndex);
                    command.setPageSize(pageSize);
                    command.setCustomerBound(true);
                    return CommandSupportManagerProviders.getCommandSupport(command.SERVICE_ID)
                            .switchIfEmpty(Mono.error(error("house_source_unavailable", 503)))
                            .flatMap(support -> support.execute(command))
                            .switchIfEmpty(Mono.error(error("house_source_unavailable", 503)));
                });
    }
}
