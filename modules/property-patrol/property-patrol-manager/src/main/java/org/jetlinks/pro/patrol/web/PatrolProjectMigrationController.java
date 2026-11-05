package org.jetlinks.pro.patrol.web;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.annotation.*;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.patrol.asset.*;
import org.jetlinks.pro.patrol.entity.*;
import org.jetlinks.pro.patrol.integration.PatrolProjectContext;
import org.jetlinks.pro.patrol.service.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;

/**
 * 已持久化数据的一次性显式归属入口；不猜测项目，不修改已归属记录，不改变巡更事实计数。
 * 请求保留原 SaaS 资产权限，每种类型另检查其 save/query 动作；不能作为越权查看旧数据的旁路。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/park/patrol/project-migrations")
@Authorize
public class PatrolProjectMigrationController {
    private final PatrolAssetSupplier supplier;
    private final PatrolConfigurationValidator validator;
    private final PatrolTaskService tasks;
    private final PatrolTaskGroupService groups;
    private final PatrolExecutionService executions;

    public record Query(PatrolAssetType type, QueryParamEntity query) { }
    public record Assignment(PatrolAssetType type, List<String> ids, String projectId) { }

    @PostMapping("/_query")
    public Mono<PagerResult<? extends PatrolConfigEntity>> query(@RequestBody Query input) {
        var query = input.query() == null ? new QueryParamEntity() : input.query().clone();
        query.setPaging(true); query.setTotal(null); query.setPageSize(Math.min(200, Math.max(1, query.getPageSize())));
        return action(input.type(), "query").then(PatrolProjectContext.current()).flatMap(context -> {
            query.toNestQuery(q -> q.and("scopeKey", context.scopeKey()).isNull("projectId"));
            return AssetsHolder.injectQueryParam(query, input.type(), "id")
                    .flatMap(scoped -> supplier.service(input.type()).queryPager(scoped)).map(page -> page);
        });
    }

    @PostMapping("/assign")
    @Transactional
    public Mono<Integer> assign(@RequestBody Assignment input) {
        if (input.ids() == null || input.ids().isEmpty() || input.ids().size() > 100)
            return Mono.error(new BusinessException("error.patrol.migration_limit"));
        return action(input.type(), "save").then(PatrolProjectContext.current()).flatMap(context -> {
            String project = context.projectForWrite(input.projectId());
            return AssetsHolder.assertPermission(input.type(), input.ids(), CrudAssetPermission.save)
                    .thenMany(Flux.fromIterable(input.ids()).distinct().concatMap(id -> migrate(input.type(), id, project)))
                    .count().map(Long::intValue);
        });
    }

    private Mono<Void> action(PatrolAssetType type, String action) {
        return Authentication.currentReactive().filter(auth -> auth.hasPermission(type.getResourceId().get(0), action))
                .switchIfEmpty(Mono.error(AccessDenyException.NoStackTrace::new)).then();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Mono<PatrolConfigEntity> migrate(PatrolAssetType type, String id, String project) {
        var service = (org.hswebframework.web.crud.service.GenericReactiveCrudService<PatrolConfigEntity, String>) (Object) supplier.service(type);
        return ((Mono<PatrolConfigEntity>) service.findById(id)).switchIfEmpty(Mono.error(AccessDenyException.NoStackTrace::new))
                .flatMap(row -> PatrolProjectContext.current().flatMap(context -> {
                    if (!context.scopeKey().equals(row.getScopeKey()) || row.getProjectId() != null)
                        return Mono.error(new BusinessException("error.patrol.project_immutable", 400));
                    row.setProjectId(project); row.setProjectName(context.projectName(project)); row.setOwnerOrganizationId(null);
                    return validator.validate(row).then(service.createUpdate()
                                    .set("projectId", project).set("projectName", row.getProjectName()).setNull("ownerOrganizationId")
                                    .where("id", id).and("scopeKey", context.scopeKey()).isNull("projectId")
                                    .execute().flatMap(changed -> changed == 1 ? Mono.empty()
                                            : Mono.error(new BusinessException("error.patrol.project_immutable", 400))))
                            .then(type == PatrolAssetType.patrolPlan ? backfillFacts(id, project, row.getProjectName()) : Mono.empty())
                            .thenReturn(row);
                }));
    }

    private Mono<Void> backfillFacts(String planId, String project, String name) {
        // 仅补充原本不存在的归属字段；任务组ID、时段、计数、打卡及漏检事实均不改写。
        return Mono.when(
                tasks.createUpdate().set("projectId", project).set("projectName", name).where("planId", planId).isNull("projectId").execute(),
                groups.createUpdate().set("projectId", project).set("projectName", name).where("planId", planId).isNull("projectId").execute(),
                executions.createUpdate().set("projectId", project).set("projectName", name).where("planId", planId).isNull("projectId").execute());
    }
}
