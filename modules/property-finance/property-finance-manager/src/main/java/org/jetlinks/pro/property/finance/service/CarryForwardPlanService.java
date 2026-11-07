package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.*;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.carry.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.*;

import java.util.*;

/** 项目自动结转配置入口，沿用旧系统同项目同科目只归一个计划的约束；冲突整笔拒绝，不静默略过。 */
@Service
@RequiredArgsConstructor
public class CarryForwardPlanService {
    private final FinanceScope scope;
    private final SubjectService subjects;
    private final ReactiveRepository<ProjectSubjectEntity, String> issued;
    private final ReactiveRepository<CarryForwardPlanEntity, String> plans;
    private final ReactiveRepository<CarryForwardSubjectEntity, String> claims;
    private final ReactiveRepository<CarryForwardPlanLogEntity, String> logs;
    private final ReactiveRepository<SubjectEntity, String> subjectRows;
    private final ReactiveRepository<StandardEntity, String> standards;

    /** 只提供本项目已下发科目，不要求配置人员拥有全局科目维护权限。 */
    public Mono<List<SubjectOption>> options(String project) {
        return scope.project(project, CrudAssetPermission.read)
                .thenMany(
                        issued.createQuery()
                                .where("projectId", project)
                                .and("deleted", false)
                                .fetch())
                .map(ProjectSubjectEntity::getSubjectId)
                .collectList()
                .flatMap(
                        ids ->
                                ids.isEmpty()
                                        ? Mono.just(List.of())
                                        : subjectRows
                                                .createQuery()
                                                .in("id", ids)
                                                .and("deleted", false)
                                                .fetch()
                                                .sort(Comparator.comparing(SubjectEntity::getCode))
                                                .map(
                                                        row ->
                                                                new SubjectOption(
                                                                        row.getId(),
                                                                        row.getName(),
                                                                        row.getCode(),
                                                                        row.getEnabled()))
                                                .collectList());
    }

    /** 标准选项复用计划查询权限，限定本项目已下发科目，不开放全局标准数据。 */
    public Mono<List<StandardOption>> standardOptions(String project) {
        return options(project)
                .flatMap(
                        ids ->
                                ids.isEmpty()
                                        ? Mono.just(List.of())
                                        : standards
                                                .createQuery()
                                                .where("projectId", project)
                                                .and("deleted", false)
                                                .in(
                                                        "subjectId",
                                                        ids.stream()
                                                                .map(SubjectOption::id)
                                                                .toList())
                                                .fetch()
                                                .sort(
                                                        Comparator.comparing(
                                                                StandardEntity::getStandardCode))
                                                .map(
                                                        row ->
                                                                new StandardOption(
                                                                        row.getId(),
                                                                        row.getSubjectId(),
                                                                        row.getStandardName(),
                                                                        row.getStandardCode()))
                                                .collectList());
    }

    public Mono<PagerResult<Detail>> query(String project, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(
                        Mono.defer(
                                () -> {
                                    var q = page(input);
                                    q.toNestQuery(
                                            n -> n.and("projectId", project).and("deleted", false));
                                    return QueryHelper.transformPageResult(
                                            QueryHelper.queryPager(q, plans::createQuery),
                                            rows ->
                                                    Flux.fromIterable(rows)
                                                            .map(CarryForwardPlanService::detail)
                                                            .collectList());
                                }));
    }

    public Mono<Detail> detail(String project, String id) {
        return scope.project(project, CrudAssetPermission.read)
                .then(row(project, id, false, false))
                .map(CarryForwardPlanService::detail);
    }

    /** 逻辑删除后审计仍保留；查询必须继续校验其原项目，不开放跨项目追溯入口。 */
    public Mono<PagerResult<CarryForwardPlanLogEntity>> history(
            String project, String id, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(row(project, id, false, true))
                .then(
                        Mono.defer(
                                () -> {
                                    var q = page(input);
                                    q.toNestQuery(
                                            n -> n.and("projectId", project).and("planId", id));
                                    return QueryHelper.queryPager(q, logs::createQuery);
                                }));
    }

    @Transactional
    public Mono<Detail> create(String project, CarryForwardPlanInput submitted) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            var input = normalized(submitted);
                                            return lockSubjects(
                                                            project, input.getSubjectIds(), true)
                                                    .then(lockStandards(project, input))
                                                    .then(
                                                            TenantHolder.currentId()
                                                                    .defaultIfEmpty(""))
                                                    .flatMap(
                                                            tenant ->
                                                                    insert(
                                                                            project, input, tenant,
                                                                            auth));
                                        }))
                .onErrorMap(this::duplicate)
                .as(MonoTracer.create("/property/finance/carry-plan/create"));
    }

    private Mono<Detail> insert(
            String project, CarryForwardPlanInput input, String tenant, Authentication auth) {
        var row = new CarryForwardPlanEntity();
        row.setId(UUID.randomUUID().toString());
        init(row, auth);
        row.setProjectId(project);
        row.setTenantId(tenant);
        row.setName(input.getName());
        row.setEnabled(input.getEnabled());
        input.setVersion(row.getVersion());
        row.setConfigJson(JSON.toJSONString(input));
        return CarryForwardAuthority.capture(auth)
                .flatMap(
                        snapshot -> {
                            row.setExecutorSnapshot(snapshot);
                            return replaceClaims(project, row.getId(), input.getSubjectIds(), auth)
                                    .then(plans.insert(row))
                                    .then(log(null, row, "CREATE", auth))
                                    .thenReturn(detail(row));
                        });
    }

    @Transactional
    public Mono<Detail> update(String project, String id, CarryForwardPlanInput submitted) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            var input = normalized(submitted);
                                            return row(project, id, false, false)
                                                    .flatMap(
                                                            before ->
                                                                    lockForEdit(
                                                                                    project, before,
                                                                                    input)
                                                                            .flatMap(
                                                                                    locked ->
                                                                                            publish(
                                                                                                    project,
                                                                                                    locked,
                                                                                                    input,
                                                                                                    "UPDATE",
                                                                                                    auth)));
                                        }))
                .onErrorMap(this::duplicate)
                .as(MonoTracer.create("/property/finance/carry-plan/update"));
    }

    private Mono<CarryForwardPlanEntity> lockForEdit(
            String project, CarryForwardPlanEntity before, CarryForwardPlanInput input) {
        version(before, input.getVersion());
        var old = detail(before).input();
        var ids = new TreeSet<>(old.getSubjectIds());
        ids.addAll(input.getSubjectIds());
        // 与科目下发/撤回采用同序；仅在稳定科目和下发行加锁后锁计划，防止并发偷占或失去下发关系。
        return lockSubjects(project, ids, false)
                .then(lockSubjects(project, input.getSubjectIds(), true))
                .then(lockStandards(project, input))
                .then(row(project, before.getId(), true, false))
                .doOnNext(locked -> version(locked, input.getVersion()));
    }

    @Transactional
    public Mono<Detail> state(String project, String id, long version, boolean enabled) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                row(project, id, false, false)
                                        .flatMap(
                                                before -> {
                                                    version(before, version);
                                                    var input = detail(before).input();
                                                    input.setVersion(version);
                                                    input.setEnabled(enabled);
                                                    return lockSubjects(
                                                                    project,
                                                                    input.getSubjectIds(),
                                                                    enabled)
                                                            .then(
                                                                    enabled
                                                                            ? lockStandards(
                                                                                    project, input)
                                                                            : Mono.empty())
                                                            .then(row(project, id, true, false))
                                                            .flatMap(
                                                                    locked ->
                                                                            publish(
                                                                                    project,
                                                                                    locked,
                                                                                    input,
                                                                                    enabled
                                                                                            ? "ENABLE"
                                                                                            : "DISABLE",
                                                                                    auth));
                                                }))
                .onErrorMap(this::duplicate)
                .as(MonoTracer.create("/property/finance/carry-plan/state"));
    }

    private Mono<Detail> publish(
            String project,
            CarryForwardPlanEntity before,
            CarryForwardPlanInput input,
            String operation,
            Authentication auth) {
        version(before, input.getVersion());
        var after = new CarryForwardPlanEntity();
        BeanUtils.copyProperties(before, after);
        after.setVersion(before.getVersion() + 1);
        after.setModifyTime(System.currentTimeMillis());
        after.setModifierId(auth.getUser().getId());
        after.setModifierName(FinanceScope.operatorName(auth));
        after.setName(input.getName());
        after.setEnabled(input.getEnabled());
        input.setVersion(after.getVersion());
        after.setConfigJson(JSON.toJSONString(input));
        return CarryForwardAuthority.capture(auth)
                .flatMap(
                        snapshot -> {
                            after.setExecutorSnapshot(snapshot);
                            return replaceClaims(
                                            project, before.getId(), input.getSubjectIds(), auth)
                                    .then(
                                            audit(plans.createUpdate(), auth, before.getVersion())
                                                    .set("name", after.getName())
                                                    .set("enabled", after.getEnabled())
                                                    .set("configJson", after.getConfigJson())
                                                    .set("executorSnapshot", snapshot)
                                                    .where("id", before.getId())
                                                    .and("projectId", project)
                                                    .and("version", before.getVersion())
                                                    .and("deleted", false)
                                                    .execute())
                                    .flatMap(SubjectService::changed)
                                    .then(log(before, after, operation, auth))
                                    .thenReturn(detail(after));
                        });
    }

    @Transactional
    public Mono<Void> delete(String project, String id, long version) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                row(project, id, false, false)
                                        .flatMap(
                                                before -> {
                                                    version(before, version);
                                                    return lockSubjects(
                                                                    project,
                                                                    detail(before)
                                                                            .input()
                                                                            .getSubjectIds(),
                                                                    false)
                                                            .then(row(project, id, true, false))
                                                            .flatMap(
                                                                    locked ->
                                                                            remove(
                                                                                    project, locked,
                                                                                    version, auth));
                                                }))
                .as(MonoTracer.create("/property/finance/carry-plan/delete"));
    }

    private Mono<Void> remove(
            String project, CarryForwardPlanEntity before, long version, Authentication auth) {
        version(before, version);
        var after = new CarryForwardPlanEntity();
        BeanUtils.copyProperties(before, after);
        after.setDeleted(true);
        after.setDeletedTime(System.currentTimeMillis());
        after.setEnabled(false);
        after.setVersion(version + 1);
        after.setModifierId(auth.getUser().getId());
        after.setModifierName(FinanceScope.operatorName(auth));
        after.setModifyTime(after.getDeletedTime());
        return audit(plans.createUpdate(), auth, version)
                .set("deleted", true)
                .set("deletedTime", after.getDeletedTime())
                .set("enabled", false)
                .where("id", before.getId())
                .and("projectId", project)
                .and("version", version)
                .and("deleted", false)
                .execute()
                .flatMap(SubjectService::changed)
                .then(release(project, before.getId(), null, auth))
                .then(log(before, after, "DELETE", auth));
    }

    private Mono<Void> lockSubjects(
            String project, Collection<String> ids, boolean requireAvailable) {
        // 配置最多100科目；排序后串行行锁使并发创建、科目维护和下发撤回遵守相同顺序。
        return Flux.fromIterable(new TreeSet<>(ids))
                .concatMap(
                        id ->
                                subjects.subject(id, true)
                                        .onErrorMap(
                                                e ->
                                                        e
                                                                        instanceof
                                                                        org.hswebframework.web
                                                                                .exception
                                                                                .BusinessException
                                                                ? error(
                                                                        "setting_subject_unavailable",
                                                                        409)
                                                                : e)
                                        .doOnNext(
                                                s -> {
                                                    if (requireAvailable
                                                            && !Boolean.TRUE.equals(s.getEnabled()))
                                                        throw error(
                                                                "setting_subject_unavailable", 409);
                                                })
                                        .then(lockIssued(project, id)))
                .then();
    }

    private Mono<Void> lockStandards(String project, CarryForwardPlanInput input) {
        var q = new QueryParamEntity();
        q.setForUpdate(true);
        // 科目及下发行已锁定，再按ID锁标准，和收费标准维护保持同序。
        return Flux.fromIterable(new TreeSet<>(input.getStandardIds()))
                .concatMap(
                        id ->
                                standards
                                        .createQuery()
                                        .setParam(q)
                                        .where("id", id)
                                        .and("projectId", project)
                                        .and("deleted", false)
                                        .in("subjectId", input.getSubjectIds())
                                        .fetchOne()
                                        .switchIfEmpty(
                                                Mono.error(error("standard_not_found", 404))))
                .then();
    }

    private Mono<ProjectSubjectEntity> lockIssued(String project, String subject) {
        var q = new QueryParamEntity();
        q.setForUpdate(true);
        return issued.createQuery()
                .setParam(q)
                .where("projectId", project)
                .and("subjectId", subject)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("setting_subject_unavailable", 409)));
    }

    private Mono<Void> replaceClaims(
            String project, String plan, List<String> ids, Authentication auth) {
        return claims.createQuery()
                .where("projectId", project)
                .and("planId", plan)
                .and("deleted", false)
                .fetch()
                .map(CarryForwardSubjectEntity::getSubjectId)
                .collectList()
                .flatMap(
                        current -> {
                            var removed = current.stream().filter(id -> !ids.contains(id)).toList();
                            var added =
                                    ids.stream()
                                            .filter(id -> !current.contains(id))
                                            .map(
                                                    subject -> {
                                                        var claim = new CarryForwardSubjectEntity();
                                                        claim.setId(UUID.randomUUID().toString());
                                                        init(claim, auth);
                                                        claim.setProjectId(project);
                                                        claim.setPlanId(plan);
                                                        claim.setSubjectId(subject);
                                                        claim.setActiveKey("ACTIVE");
                                                        return claim;
                                                    })
                                            .toList();
                            return (removed.isEmpty()
                                            ? Mono.<Void>empty()
                                            : release(project, plan, removed, auth))
                                    .then(
                                            added.isEmpty()
                                                    ? Mono.empty()
                                                    : claims.insertBatch(Mono.just(added)).then());
                        });
    }

    private Mono<Void> release(String project, String plan, List<String> ids, Authentication auth) {
        var update =
                claims.createUpdate()
                        .set("deleted", true)
                        .set("deletedTime", System.currentTimeMillis())
                        .set("modifierId", auth.getUser().getId())
                        .set("modifierName", FinanceScope.operatorName(auth))
                        .set("modifyTime", System.currentTimeMillis())
                        .setNull("activeKey")
                        .where("projectId", project)
                        .and("planId", plan)
                        .and("deleted", false);
        if (ids != null) update.in("subjectId", ids);
        return update.execute().then();
    }

    private Mono<Void> log(
            CarryForwardPlanEntity before,
            CarryForwardPlanEntity after,
            String operation,
            Authentication auth) {
        var log = new CarryForwardPlanLogEntity();
        log.setId(UUID.randomUUID().toString());
        log.setProjectId(after.getProjectId());
        log.setPlanId(after.getId());
        log.setOperation(operation);
        if (before != null) log.setBeforeJson(snapshot(before));
        log.setAfterJson(snapshot(after));
        log.setOperatorId(auth.getUser().getId());
        log.setOperatorName(FinanceScope.operatorName(auth));
        log.setCreateTime(System.currentTimeMillis());
        return logs.insert(log).then();
    }

    private static String snapshot(CarryForwardPlanEntity row) {
        // Fastjson1不识别record访问器；显式保存实际业务配置，不把后台租户或空对象写成审计。
        return JSON.toJSONString(
                Map.of(
                        "id",
                        row.getId(),
                        "version",
                        row.getVersion(),
                        "deleted",
                        row.getDeleted(),
                        "input",
                        detail(row).input()));
    }

    private Mono<CarryForwardPlanEntity> row(
            String project, String id, boolean lock, boolean includeDeleted) {
        var q = new QueryParamEntity();
        q.setForUpdate(lock);
        var query = plans.createQuery().setParam(q).where("id", id).and("projectId", project);
        if (!includeDeleted) query.and("deleted", false);
        return query.fetchOne().switchIfEmpty(Mono.error(error("carry_plan_not_found", 404)));
    }

    private static QueryParamEntity page(QueryParamEntity input) {
        var q = SubjectService.page(input == null ? new QueryParamEntity() : input);
        if (q.getSorts().isEmpty()) q.toQuery().orderByDesc("createTime").orderByDesc("id");
        return q;
    }

    private static CarryForwardPlanInput normalized(CarryForwardPlanInput input) {
        CarryForwardRules.validate(input);
        var copy = JSON.parseObject(JSON.toJSONString(input), CarryForwardPlanInput.class);
        copy.setName(copy.getName().trim());
        copy.setSubjectIds(copy.getSubjectIds().stream().sorted().toList());
        copy.setStandardIds(copy.getStandardIds().stream().sorted().toList());
        return copy;
    }

    private static Detail detail(CarryForwardPlanEntity row) {
        var input = JSON.parseObject(row.getConfigJson(), CarryForwardPlanInput.class);
        input.setName(row.getName());
        input.setVersion(row.getVersion());
        input.setEnabled(row.getEnabled());
        return new Detail(row, input);
    }

    private static void version(CarryForwardPlanEntity row, Long submitted) {
        if (submitted == null || !Objects.equals(submitted, row.getVersion()))
            throw error("version_conflict", 409);
    }

    private Throwable duplicate(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof org.springframework.dao.DuplicateKeyException
                    || cause
                            instanceof org.hswebframework.ezorm.rdb.exception.DuplicateKeyException)
                return error("carry_subject_conflict", 409);
        return failure;
    }

    /** input为可读配置，plan只提供身份和版本；不返回后台租户、内部原始JSON。 */
    public record Detail(CarryForwardPlanEntity plan, CarryForwardPlanInput input) {}

    public record SubjectOption(String id, String name, String code, Boolean enabled) {}

    public record StandardOption(String id, String subjectId, String name, String code) {}
}
