package org.jetlinks.pro.property.base.service;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.space.SaasSpaceInfo;
import org.jetlinks.sdk.server.commons.cmd.QueryListCommand;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * 通过固定 SaaS 命令读取有界的完整空间与类型快照，由基础服务同步器调用。
 * 不继承操作者的租户、资产筛选或事务；只读来源，不写 Cloud 数据。
 * 超限、重复、缺失类型或命令失败均拒绝整轮同步，不用部分结果覆盖镜像。
 */
@Component
public class SaasSpaceSource {
    public Mono<List<SaasSpaceInfo>> fetchAll() {
        // 空间命令返回顺序可能重排，不能把 ID 不递增当成来源缺失。
        // 显式不分页、最多接收上限+1 条，避免按偏移分页依赖返回顺序或静默截断。
        return snapshot(false)
            .flatMap(this::fillTypes)
            .timeout(Duration.ofSeconds(90))
            .contextWrite(ignored -> Context.empty());
    }

    private Mono<List<SaasSpaceInfo>> snapshot(boolean type) {
        var query = new QueryParamEntity().noPaging();
        return (type ? types(query) : spaces(query))
            .take(SpaceSnapshot.MAX_NODES + 1L)
            .collectList()
            .map(rows -> {
                if (rows.size() > SpaceSnapshot.MAX_NODES) throw SpaceSnapshot.invalid();
                var ids = new HashSet<String>();
                for (var row : rows) {
                    SpaceSnapshot.validate(row);
                    if (!ids.add(row.getId())) throw SpaceSnapshot.invalid();
                }
                return rows;
            });
    }

    private Mono<List<SaasSpaceInfo>> fillTypes(List<SaasSpaceInfo> spaces) {
        if (spaces.stream().noneMatch(space -> space.getSpaceTypeId() != null))
            return Mono.just(spaces);
        return snapshot(true).map(types -> {
            var names = new HashMap<String, String>();
            types.forEach(type -> names.put(type.getId(), type.getName()));
            for (var space : spaces) {
                if (space.getSpaceTypeId() == null) continue;
                var name = names.get(space.getSpaceTypeId());
                if (name == null) throw SpaceSnapshot.invalid();
                space.setSpaceTypeName(name);
            }
            return spaces;
        });
    }

    protected Flux<SaasSpaceInfo> spaces(QueryParamEntity query) {
        return commandList("spaceService:space", query);
    }

    protected Flux<SaasSpaceInfo> types(QueryParamEntity query) {
        return commandList("spaceService:space-type", query);
    }

    private Flux<SaasSpaceInfo> commandList(String serviceId, QueryParamEntity query) {
        return CommandSupportManagerProviders.getCommandSupport(serviceId)
            .switchIfEmpty(Mono.error(new BusinessException.NoStackTrace(
                "error.property_space_source_unavailable", 503)))
            .flatMapMany(support -> support.execute(listCommand(query)));
    }

    static QueryListCommand<SaasSpaceInfo> listCommand(QueryParamEntity query) {
        var command = QueryListCommand.of(SaasSpaceInfo.class);
        command.withQueryParam(query);
        return command;
    }
}
