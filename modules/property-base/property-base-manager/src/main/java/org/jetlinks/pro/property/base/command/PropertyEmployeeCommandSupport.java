package org.jetlinks.pro.property.base.command;

import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.exception.UnAuthorizedException;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.core.annotation.command.CommandHandler;
import org.jetlinks.pro.annotation.command.CommandService;
import org.jetlinks.pro.property.base.entity.PropertyUserEntity;
import org.jetlinks.pro.property.base.entity.PropertyUserOrganizationEntity;
import org.jetlinks.pro.property.base.user.PropertyEmployeeInfo;
import org.jetlinks.pro.property.base.service.PropertyUserScope;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.List;

/** 员工只读公共边界：目录遵循 user 资产，执行人本人校验不提升为目录管理员。 */
@Component
@Authorize
@RequiredArgsConstructor
@CommandService(id = QueryPropertyEmployeePageCommand.SERVICE_ID, name = "物业员工服务")
public class PropertyEmployeeCommandSupport {
    private final ReactiveRepository<PropertyUserEntity, String> users;
    private final ReactiveRepository<PropertyUserOrganizationEntity, String> organizations;
    private final PropertyUserScope userScope;

    @CommandHandler(name = "查询有效员工")
    public Mono<PagerResult<PropertyEmployeeInfo>> query(QueryPropertyEmployeePageCommand command) {
        var query = command.getQuery().clone();
        query.setTotal(null);
        query.setPaging(true);
        query.setPageSize(Math.max(1, Math.min(200, query.getPageSize())));
        query.setPageIndex(Math.max(0, query.getPageIndex()));
        query.toNestQuery(q -> q.and("deleted", false).and("employee", true).and("status", 1));
        // user 的 Scope 成员与组织权限由认证服务解析；本库仅执行有效员工和直接组织关系条件。
        // Reuse the shared directory owner: user asset SQL cannot run in the mirror database.
        return userScope.query(query)
                .flatMap(scoped -> QueryHelper.queryPager(scoped, users::createQuery))
                .flatMap(page -> QueryHelper.transformPageResult(Mono.just(page), this::enrich));
    }

    @CommandHandler(name = "读取本人有效员工身份")
    public Mono<PropertyEmployeeInfo> current(GetCurrentPropertyEmployeeCommand command) {
        return authenticated().flatMap(auth -> users.createQuery().where("id", auth.getUser().getId())
                .and("employee", true).and("status", 1).and("deleted", false).fetchOne())
                .flatMap(user -> enrich(List.of(user)).map(list -> list.get(0)));
    }

    private Mono<Authentication> authenticated() {
        return Authentication.currentReactive().switchIfEmpty(Mono.error(UnAuthorizedException.NoStackTrace::new));
    }

    private Mono<List<PropertyEmployeeInfo>> enrich(List<PropertyUserEntity> rows) {
        if (rows.isEmpty()) return Mono.just(List.of());
        return organizations.createQuery().in("userId", rows.stream().map(PropertyUserEntity::getId).toList())
                .and("deleted", false).fetch().collectMultimap(PropertyUserOrganizationEntity::getUserId)
                .map(byUser -> rows.stream().map(user -> new PropertyEmployeeInfo(user.getId(), user.getName(),
                        user.getTelephone(), byUser.getOrDefault(user.getId(), List.of()).stream()
                        .map(PropertyUserOrganizationEntity::getOrgId).distinct().toList())).toList());
    }
}
