package org.jetlinks.pro.property.base.service;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.space.SaasSpaceInfo;
import org.jetlinks.sdk.server.commons.cmd.QueryPagerCommand;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 固定 SaaS 空间命令来源，读取完整分页快照，不继承调用者租户、资产筛选或事务。 */
@Component
@lombok.extern.slf4j.Slf4j
public class SaasSpaceSource {
    static final int PAGE_SIZE = 200;

    public Mono<List<SaasSpaceInfo>> fetchAll() {
        // 整轮至多 20000 条、90 秒；拒绝截断而非把部分页面当成可删除的完整来源。
        return Mono.defer(() -> readPage(0, -1, new ArrayList<>(), false))
                .flatMap(this::fillTypes)
                .timeout(Duration.ofSeconds(90))
                .contextWrite(ignored -> Context.empty());
    }

    private Mono<List<SaasSpaceInfo>> readPage(
            int pageIndex, long expected, List<SaasSpaceInfo> result, boolean type) {
        if (pageIndex > SpaceSnapshot.MAX_NODES / PAGE_SIZE)
            return Mono.error(SpaceSnapshot.invalid());
        var query = new QueryParamEntity();
        query.setPageIndex(pageIndex);
        query.setPageSize(PAGE_SIZE);
        query.orderBy("id");
        return (type ? typePage(query) : page(query))
                .switchIfEmpty(Mono.error(SpaceSnapshot.invalid()))
                .flatMap(page -> acceptPage(page, query, expected, result, type));
    }

    private Mono<List<SaasSpaceInfo>> acceptPage(
            PagerResult<SaasSpaceInfo> page,
            QueryParamEntity query,
            long expected,
            List<SaasSpaceInfo> result,
            boolean type) {
        long total = page.getTotal();
        log.debug("Space source page: type={}, requested={}, returned={}, total={}, rows={}", type,
            query.getPageIndex(),page.getPageIndex(),total,page.getData()==null?-1:page.getData().size());
        int wanted =
                (int)
                        Math.min(
                                PAGE_SIZE,
                                Math.max(0, total - (long) query.getPageIndex() * PAGE_SIZE));
        if (total < 0
                || total > SpaceSnapshot.MAX_NODES
                || expected >= 0 && expected != total
                || page.getData() == null
                || page.getData().size() != wanted
                || page.getPageIndex() != query.getPageIndex())
            return Mono.error(SpaceSnapshot.invalid());
        String previous = result.isEmpty() ? null : result.get(result.size() - 1).getId();
        for (var node : page.getData()) {
            try { SpaceSnapshot.validate(node); }
            catch (BusinessException error) {
                log.warn("Space source rejected node: type={}, idPresent={}, namePresent={}, parentPresent={}, spaceTypePresent={}",
                    type,node.getId()!=null,node.getName()!=null,node.getParentId()!=null,node.getSpaceTypeId()!=null);
                throw error;
            }
            if (previous != null && previous.compareTo(node.getId()) >= 0)
                throw SpaceSnapshot.invalid();
            result.add(node);
            previous = node.getId();
        }
        return result.size() == total
                ? Mono.just(result)
                : readPage(query.getPageIndex() + 1, total, result, type);
    }

    private Mono<List<SaasSpaceInfo>> fillTypes(List<SaasSpaceInfo> spaces) {
        if (spaces.stream().noneMatch(space -> space.getSpaceTypeId() != null))
            return Mono.just(spaces);
        // 类型页和空间页任意一页失败都会拒绝本轮快照，不能悄悄抹掉既有类型名称。
        return readPage(0, -1, new ArrayList<>(), true)
                .map(
                        types -> {
                            var byId = new java.util.HashMap<String, String>();
                            types.forEach(type -> byId.put(type.getId(), type.getName()));
                            spaces.forEach(
                                    space ->
                                            space.setSpaceTypeName(
                                                    byId.get(space.getSpaceTypeId())));
                            return spaces;
                        });
    }

    protected Mono<PagerResult<SaasSpaceInfo>> typePage(QueryParamEntity query) {
        return commandPage("spaceService:space-type", query);
    }

    protected Mono<PagerResult<SaasSpaceInfo>> page(QueryParamEntity query) {
        return commandPage("spaceService:space", query);
    }

    private Mono<PagerResult<SaasSpaceInfo>> commandPage(String serviceId, QueryParamEntity query) {
        return CommandSupportManagerProviders.getCommandSupport(serviceId)
                .switchIfEmpty(
                        Mono.error(
                                new BusinessException.NoStackTrace(
                                        "error.property_space_source_unavailable", 503)))
                .flatMap(support -> support.execute(pageCommand(query)));
    }

    static QueryPagerCommand<SaasSpaceInfo> pageCommand(QueryParamEntity query) {
        var command = QueryPagerCommand.of(SaasSpaceInfo.class);
        command.withQueryParam(query);
        return command;
    }
}
