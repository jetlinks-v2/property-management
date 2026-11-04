package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import org.hswebframework.web.api.crud.entity.*;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.sdk.server.auth.UserDetail;
import org.jetlinks.sdk.server.auth.cmd.QueryUserDetailPageCommand;
import org.jetlinks.sdk.server.commons.cmd.QueryPagerCommand;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

/** 领用账号只通过SaaS公开命令读取；保留认证和租户，不复用后台同步的全权限上下文。 */
@Component
public class AllocationRecipientSource {
    public record Recipient(String id, String username, String name) {}

    public Mono<Recipient> require(String id) {
        if (id == null || id.isBlank() || id.length() > 64)
            return Mono.error(error("allocation_recipient_invalid", 400));
        var q = new QueryParamEntity();
        q.setPageSize(1);
        q.and("id", "eq", id);
        return page(q).flatMap(
                        p -> {
                            if (p.getTotal() != 1
                                    || p.getData() == null
                                    || p.getData().size() != 1
                                    || !id.equals(p.getData().get(0).id()))
                                return Mono.error(error("allocation_recipient_invalid", 400));
                            return Mono.just(p.getData().get(0));
                        });
    }

    public Mono<PagerResult<Recipient>> options(int pageIndex, int pageSize, String keywords) {
        var q = new QueryParamEntity();
        q.setPageIndex(Math.max(0, pageIndex));
        q.setPageSize(Math.max(1, Math.min(50, pageSize)));
        q.orderBy("id");
        if (keywords != null && !keywords.isBlank()) {
            if (keywords.length() > 128)
                return Mono.error(error("allocation_recipient_invalid", 400));
            q.toNestQuery(
                    n ->
                            n.and("name", "like", "%" + keywords.trim() + "%")
                                    .or("username", "like", "%" + keywords.trim() + "%"));
        }
        return page(q);
    }

    private Mono<PagerResult<Recipient>> page(QueryParamEntity query) {
        query.setPaging(true);
        query.setForUpdate(false);
        query.toNestQuery(n -> n.and("status", 1));
        var command = new QueryUserDetailPageCommand();
        command.withConverter(QueryPagerCommand.of(UserDetail.class)::createResponseData);
        command.withQueryParam(query);
        return CommandSupportManagerProviders.getCommandSupport("authService:user")
                .switchIfEmpty(Mono.error(error("allocation_user_source_unavailable", 503)))
                .flatMap(s -> s.execute(command))
                .map(
                        p -> {
                            if (p.getData() == null || p.getData().size() > query.getPageSize())
                                throw error("allocation_user_source_unavailable", 502);
                            var out = new ArrayList<Recipient>();
                            for (var u : p.getData()) {
                                if (u.getId() == null
                                        || u.getId().length() > 64
                                        || u.getUsername() == null
                                        || u.getUsername().isBlank()
                                        || u.getUsername().length() > 128
                                        || !Integer.valueOf(1).equals(u.getStatus()))
                                    throw error("allocation_user_source_unavailable", 502);
                                var name =
                                        u.getName() == null || u.getName().isBlank()
                                                ? u.getUsername()
                                                : u.getName();
                                if (name.length() > 128)
                                    throw error("allocation_user_source_unavailable", 502);
                                out.add(new Recipient(u.getId(), u.getUsername(), name));
                            }
                            return PagerResult.of(p.getTotal(), out, query);
                        })
                .timeout(Duration.ofSeconds(10));
    }
}
