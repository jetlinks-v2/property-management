package org.jetlinks.pro.property.base.service;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.sdk.server.auth.UserDetail;
import org.jetlinks.sdk.server.auth.UserInfo;
import org.jetlinks.sdk.server.auth.cmd.QueryUserDetailPageCommand;
import org.jetlinks.sdk.server.commons.cmd.QueryListCommand;
import org.jetlinks.sdk.server.commons.cmd.QueryPagerCommand;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.*;

/** 固定 SaaS 命令来源，分页读取和缺失复核均不继承操作者的筛选、资产或事务上下文。 */
@Component
public class SaasUserSource {
    @Value("${property.user-sync.max-users:100000}")
    private int maxUsers = 100000;

    @Value("${property.user-sync.timeout-seconds:120}")
    private long timeoutSeconds = 120;

    @Value("${property.user-sync.page-timeout-seconds:60}")
    private long pageTimeoutSeconds = 60;

    static final int PAGE_SIZE = 200;

    public Mono<Map<String, UserDetail>> fetchAll() {
        return Mono.defer(() -> readPage(0, -1, new LinkedHashMap<>()))
                // 分页快照服从整轮同步预算，避免人数增加后被固定 90 秒提前取消。
                .timeout(Duration.ofSeconds(Math.max(10, timeoutSeconds)))
                .contextWrite(ignored -> Context.empty());
    }

    private Mono<Map<String, UserDetail>> readPage(
            int pageIndex, long expected, Map<String, UserDetail> result) {
        if (pageIndex > Math.ceil((double) maxUsers / PAGE_SIZE)) return Mono.error(invalid());
        var query = new QueryParamEntity();
        query.setPageIndex(pageIndex);
        query.setPageSize(PAGE_SIZE);
        query.orderBy("id");
        return page(query)
                .flatMap(
                        page -> {
                            long total = page.getTotal();
                            if (total < 0
                                    || total > maxUsers
                                    || (expected >= 0 && expected != total)
                                    || page.getData() == null
                                    || page.getData().size() > PAGE_SIZE
                                    || page.getPageIndex() != pageIndex)
                                return Mono.error(invalid());
                            int wanted =
                                    (int)
                                            Math.min(
                                                    PAGE_SIZE,
                                                    Math.max(
                                                            0,
                                                            total - (long) pageIndex * PAGE_SIZE));
                            if (page.getData().size() != wanted) return Mono.error(invalid());
                            String previous =
                                    result.isEmpty()
                                            ? null
                                            : result.keySet().stream()
                                                    .reduce((a, b) -> b)
                                                    .orElse(null);
                            for (var user : page.getData()) {
                                validate(user);
                                if ((previous != null && previous.compareTo(user.getId()) >= 0)
                                        || result.putIfAbsent(user.getId(), user) != null)
                                    return Mono.error(invalid());
                                previous = user.getId();
                            }
                            if (result.size() == total) return Mono.just(result);
                            return readPage(pageIndex + 1, total, result);
                        });
    }

    /** 一次精确 ID 查询返回 total=0 才是明确不存在；超时和异常向上传递，不能当删除。 */
    public Mono<Optional<UserDetail>> find(String id) {
        var query = new QueryParamEntity();
        query.setPageSize(1);
        query.and("id", "eq", id);
        return page(query)
                .map(
                        page -> {
                            if (page.getData() == null
                                    || page.getTotal() > 1
                                    || page.getTotal() != page.getData().size()) throw invalid();
                            if (page.getTotal() == 0) return Optional.<UserDetail>empty();
                            var user = page.getData().get(0);
                            validate(user);
                            if (!id.equals(user.getId())) throw invalid();
                            return Optional.of(user);
                        })
                .timeout(Duration.ofSeconds(10))
                .contextWrite(ignored -> Context.empty());
    }

    /**
     * 员工目录按候选 ID 读取调用者可见的 SaaS 用户。与后台镜像同步不同，此方法保留认证及应用 Scope；
     * user 资产条件只交给认证服务解析，不在物业库执行跨库 SQL。空结果为无权限，服务异常直接传播。
     */
    public Flux<String> authorizedIds(List<String> ids) {
        if (ids.isEmpty()) return Flux.empty();
        return Flux.fromIterable(ids).distinct().buffer(PAGE_SIZE).concatMap(batch -> {
            var query = QueryParamEntity.newQuery().in("id", batch).getParam();
            query.setPaging(false);
            query.setIncludes(Set.of("id"));
            // owner 注入权限，消费方只限定候选；保留请求身份、租户和应用Scope。
            return accountIds(query).filter(batch::contains);
        }).distinct();
    }

    protected Flux<String> accountIds(QueryParamEntity query) {
        var command = QueryListCommand.of(UserInfo.class);
        command.withQueryParam(query);
        return CommandSupportManagerProviders.getCommandSupport("authService:user-directory")
                .switchIfEmpty(Mono.error(new BusinessException.NoStackTrace(
                        "error.property_user_source_unavailable", 503)))
                .flatMapMany(support -> support.execute(command))
                .map(UserInfo::getId).timeout(Duration.ofSeconds(10));
    }

    protected Mono<PagerResult<UserDetail>> page(QueryParamEntity query) {
        return CommandSupportManagerProviders.getCommandSupport("authService:user")
                .switchIfEmpty(
                        Mono.error(
                                new BusinessException.NoStackTrace(
                                        "error.property_user_source_unavailable", 503)))
                .flatMap(
                        support ->
                                support.execute(pageCommand(query))
                                        .flatMap(page -> restoreAccountTypes(support, page)))
                .timeout(Duration.ofSeconds(Math.max(1, pageTimeoutSeconds)))
                .checkpoint("property-user-source-page-" + query.getPageIndex());
    }

    private Mono<PagerResult<UserDetail>> restoreAccountTypes(
            org.jetlinks.core.command.CommandSupport support, PagerResult<UserDetail> page) {
        if (page.getData() == null) return Mono.error(invalid());
        var missing =
                page.getData().stream()
                        .filter(user -> user.getType() == null)
                        .map(UserDetail::getId)
                        .toList();
        if (missing.isEmpty()) return Mono.just(page);
        // 当前 SDK 详情 typeId -> type 未自动映射；标准用户命令直接返回正确 type。
        var types = QueryListCommand.of(UserInfo.class);
        var params = new QueryParamEntity().noPaging();
        params.and("id", "in", missing);
        types.withQueryParam(params);
        return support.execute(types)
                .take(missing.size() + 1L)
                .collectList()
                .map(values -> fillAccountTypes(page, missing, values));
    }

    private PagerResult<UserDetail> fillAccountTypes(
            PagerResult<UserDetail> page, List<String> requested, List<UserInfo> values) {
        var byId = new HashMap<String, UserInfo>();
        for (var value : values) {
            if (!requested.contains(value.getId())
                    || byId.putIfAbsent(value.getId(), value) != null) throw invalid();
        }
        for (var user : page.getData()) {
            if (user.getType() != null) continue;
            var info = byId.get(user.getId());
            if (info == null) throw invalid();
            user.setType(info.getType());
        }
        return page;
    }

    static QueryUserDetailPageCommand pageCommand(QueryParamEntity query) {
        var command = new QueryUserDetailPageCommand();
        // 显式 SDK 子命令缺少内层 data 转换；沿用标准工厂还原 RPC 的 Map 及嵌套组织。
        command.withConverter(QueryPagerCommand.of(UserDetail.class)::createResponseData);
        command.withQueryParam(query);
        return command;
    }

    void validateSize(int size) {
        if (size > maxUsers) throw invalid();
    }

    static void validate(UserDetail user) {
        if (user == null
                || !text(user.getId(), 64)
                || !text(user.getUsername(), 128)
                || !text(user.getType(), 64)
                || user.getStatus() == null
                || (user.getStatus() != 0 && user.getStatus() != 1)) throw invalid();
        optional(user.getName(), 128);
        optional(user.getTelephone(), 64);
        optional(user.getEmail(), 256);
        var ids = new HashSet<String>();
        if (user.getOrgList() != null)
            for (var org : user.getOrgList()) {
                if (org == null || !text(org.getId(), 64) || !ids.add(org.getId())) throw invalid();
                optional(org.getName(), 128);
            }
    }

    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }

    private static void optional(String value, int max) {
        if (value != null && value.length() > max) throw invalid();
    }

    static BusinessException invalid() {
        return new BusinessException.NoStackTrace("error.property_user_snapshot_invalid", 502);
    }
}
