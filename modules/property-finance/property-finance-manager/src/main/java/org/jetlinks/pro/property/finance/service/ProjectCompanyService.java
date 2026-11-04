package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.audit;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.init;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.FinanceCompanyEntity;
import org.jetlinks.pro.property.finance.entity.FinanceProjectCompanyEntity;
import org.jetlinks.pro.property.finance.entity.RecordedInfoEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** 项目引用共享公司；默认切换锁定当前关系集合，数据库唯一键兜底首次并发关联。 */
@Service
@RequiredArgsConstructor
public class ProjectCompanyService {
    private final FinanceScope scope;
    private final InvoiceCompanyService companies;
    private final ReactiveRepository<FinanceProjectCompanyEntity, String> relations;
    private final ReactiveRepository<RecordedInfoEntity, String> recorded;

    public Flux<Row> list(String projectId) {
        return scope.project(projectId, CrudAssetPermission.read)
                .thenMany(
                        relations
                                .createQuery()
                                .where("projectId", projectId)
                                .and("deleted", false)
                                .fetch())
                .concatMap(
                        relation ->
                                companies
                                        .company(relation.getCompanyId(), false)
                                        .map(company -> new Row(relation, company)));
    }

    public Mono<InvoiceCompanyService.Detail> detail(String projectId, String id) {
        return scope.project(projectId, CrudAssetPermission.read)
                .then(
                        relations
                                .createQuery()
                                .where("id", id)
                                .and("projectId", projectId)
                                .and("deleted", false)
                                .fetchOne())
                .switchIfEmpty(Mono.error(error("company_link_not_found", 404)))
                .flatMap(relation -> companies.detail(relation.getCompanyId()));
    }

    /** 绑定选择器只需项目动作权限，不向项目用户授予全局税号维护权限。 */
    public Mono<org.hswebframework.web.api.crud.entity.PagerResult<FinanceCompanyEntity>>
            candidates(String projectId, QueryParamEntity query) {
        return scope.project(projectId, CrudAssetPermission.read).then(companies.query(query));
    }

    /** 新建共享公司并关联项目必须同事务；绑定失败不能留下半成品主档。 */
    @Transactional
    public Mono<Row> createAndBind(
            String projectId, org.jetlinks.pro.property.finance.tax.InvoiceCompanyInput input) {
        return scope.project(projectId, CrudAssetPermission.save)
                .then(companies.create(input))
                .flatMap(detail -> bind(projectId, detail.company().getId()));
    }

    @Transactional
    public Mono<Row> bind(String projectId, String companyId) {
        return scope.project(projectId, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                companies
                                        .company(companyId, true)
                                        .flatMap(
                                                company ->
                                                        locked(projectId)
                                                                .collectList()
                                                                .flatMap(
                                                                        active ->
                                                                                relations
                                                                                        .createQuery()
                                                                                        .where(
                                                                                                "projectId",
                                                                                                projectId)
                                                                                        .and(
                                                                                                "companyId",
                                                                                                companyId)
                                                                                        .fetchOne()
                                                                                        .flatMap(
                                                                                                old -> {
                                                                                                    if (!old
                                                                                                            .getDeleted())
                                                                                                        return Mono
                                                                                                                .<FinanceProjectCompanyEntity>
                                                                                                                        error(
                                                                                                                                error(
                                                                                                                                        "company_already_bound",
                                                                                                                                        409));
                                                                                                    var
                                                                                                            update =
                                                                                                                    audit(
                                                                                                                                    relations
                                                                                                                                            .createUpdate(),
                                                                                                                                    auth,
                                                                                                                                    old
                                                                                                                                            .getVersion())
                                                                                                                            .set(
                                                                                                                                    "deleted",
                                                                                                                                    false)
                                                                                                                            .setNull(
                                                                                                                                    "deletedTime");
                                                                                                    if (active
                                                                                                            .isEmpty())
                                                                                                        update
                                                                                                                .set(
                                                                                                                        "defaultKey",
                                                                                                                        projectId);
                                                                                                    else
                                                                                                        update
                                                                                                                .setNull(
                                                                                                                        "defaultKey");
                                                                                                    return update.where(
                                                                                                                    "id",
                                                                                                                    old
                                                                                                                            .getId())
                                                                                                            .and(
                                                                                                                    "version",
                                                                                                                    old
                                                                                                                            .getVersion())
                                                                                                            .execute()
                                                                                                            .flatMap(
                                                                                                                    count ->
                                                                                                                            count
                                                                                                                                            == 1
                                                                                                                                    ? relations
                                                                                                                                            .findById(
                                                                                                                                                    old
                                                                                                                                                            .getId())
                                                                                                                                    : Mono
                                                                                                                                            .error(
                                                                                                                                                    error(
                                                                                                                                                            "version_conflict",
                                                                                                                                                            409)));
                                                                                                })
                                                                                        .switchIfEmpty(
                                                                                                Mono
                                                                                                        .defer(
                                                                                                                () -> {
                                                                                                                    var
                                                                                                                            relation =
                                                                                                                                    new FinanceProjectCompanyEntity();
                                                                                                                    relation
                                                                                                                            .setId(
                                                                                                                                    UUID.randomUUID()
                                                                                                                                            .toString());
                                                                                                                    relation
                                                                                                                            .setProjectId(
                                                                                                                                    projectId);
                                                                                                                    relation
                                                                                                                            .setCompanyId(
                                                                                                                                    companyId);
                                                                                                                    relation
                                                                                                                            .setDefaultKey(
                                                                                                                                    active
                                                                                                                                                    .isEmpty()
                                                                                                                                            ? projectId
                                                                                                                                            : null);
                                                                                                                    init(
                                                                                                                            relation,
                                                                                                                            auth);
                                                                                                                    return relations
                                                                                                                            .insert(
                                                                                                                                    relation)
                                                                                                                            .thenReturn(
                                                                                                                                    relation);
                                                                                                                }))
                                                                                        .map(
                                                                                                relation ->
                                                                                                        new Row(
                                                                                                                relation,
                                                                                                                company)))))
                .onErrorMap(this::duplicate)
                .as(MonoTracer.create("/property/finance/project-company/bind"));
    }

    @Transactional
    public Mono<Void> setDefault(String projectId, String id, long version) {
        return scope.project(projectId, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                locked(projectId)
                                        .collectList()
                                        .flatMap(
                                                active -> {
                                                    var selected =
                                                            active.stream()
                                                                    .filter(
                                                                            row ->
                                                                                    row.getId()
                                                                                            .equals(
                                                                                                    id))
                                                                    .findFirst()
                                                                    .orElseThrow(
                                                                            () ->
                                                                                    error(
                                                                                            "company_link_not_found",
                                                                                            404));
                                                    if (selected.getVersion() != version)
                                                        return Mono.<Void>error(
                                                                error("version_conflict", 409));
                                                    if (projectId.equals(selected.getDefaultKey()))
                                                        return Mono.<Void>empty();
                                                    return Flux.fromIterable(active)
                                                            .filter(
                                                                    row ->
                                                                            row.getDefaultKey()
                                                                                    != null)
                                                            .concatMap(
                                                                    row ->
                                                                            audit(
                                                                                            relations
                                                                                                    .createUpdate(),
                                                                                            auth,
                                                                                            row
                                                                                                    .getVersion())
                                                                                    .setNull(
                                                                                            "defaultKey")
                                                                                    .where(
                                                                                            "id",
                                                                                            row
                                                                                                    .getId())
                                                                                    .execute())
                                                            .then(
                                                                    audit(
                                                                                    relations
                                                                                            .createUpdate(),
                                                                                    auth,
                                                                                    version)
                                                                            .set(
                                                                                    "defaultKey",
                                                                                    projectId)
                                                                            .where("id", id)
                                                                            .and("version", version)
                                                                            .execute())
                                                            .flatMap(
                                                                    count ->
                                                                            count == 1
                                                                                    ? Mono
                                                                                            .<Void>
                                                                                                    empty()
                                                                                    : Mono
                                                                                            .<Void>
                                                                                                    error(
                                                                                                            error(
                                                                                                                    "version_conflict",
                                                                                                                    409)));
                                                }))
                .onErrorMap(this::duplicate)
                .as(MonoTracer.create("/property/finance/project-company/default"));
    }

    @Transactional
    public Mono<Void> unbind(String projectId, String id, long version) {
        return scope.project(projectId, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                relations
                                        .createQuery()
                                        .where("id", id)
                                        .and("projectId", projectId)
                                        .and("deleted", false)
                                        .fetchOne()
                                        .switchIfEmpty(
                                                Mono.error(error("company_link_not_found", 404)))
                                        // 所有入账引用均公司锁在先，关系锁在后，防止新增引用穿过解除检查。
                                        .flatMap(
                                                link ->
                                                        companies.company(
                                                                link.getCompanyId(), true))
                                        .then(locked(projectId).collectList())
                                        .flatMap(
                                                active -> {
                                                    var selected =
                                                            active.stream()
                                                                    .filter(
                                                                            row ->
                                                                                    row.getId()
                                                                                            .equals(
                                                                                                    id))
                                                                    .findFirst()
                                                                    .orElseThrow(
                                                                            () ->
                                                                                    error(
                                                                                            "company_link_not_found",
                                                                                            404));
                                                    if (selected.getVersion() != version)
                                                        return Mono.<Void>error(
                                                                error("version_conflict", 409));
                                                    if (selected.getDefaultKey() != null)
                                                        return Mono.<Void>error(
                                                                error(
                                                                        "company_default_required",
                                                                        409));
                                                    return recorded.createQuery()
                                                            .where("projectId", projectId)
                                                            .and(
                                                                    "companyId",
                                                                    selected.getCompanyId())
                                                            .and("deleted", false)
                                                            .count()
                                                            .flatMap(
                                                                    count ->
                                                                            count > 0
                                                                                    ? Mono.error(
                                                                                            error(
                                                                                                    "company_referenced",
                                                                                                    409))
                                                                                    : Mono.empty())
                                                            .then(
                                                                    audit(
                                                                                    relations
                                                                                            .createUpdate(),
                                                                                    auth,
                                                                                    version)
                                                                            .set("deleted", true)
                                                                            .set(
                                                                                    "deletedTime",
                                                                                    System
                                                                                            .currentTimeMillis())
                                                                            .where("id", id)
                                                                            .and("version", version)
                                                                            .execute()
                                                                            .flatMap(
                                                                                    count ->
                                                                                            count
                                                                                                            == 1
                                                                                                    ? Mono
                                                                                                            .<Void>
                                                                                                                    empty()
                                                                                                    : Mono
                                                                                                            .<Void>
                                                                                                                    error(
                                                                                                                            error(
                                                                                                                                    "version_conflict",
                                                                                                                                    409))));
                                                }))
                .as(MonoTracer.create("/property/finance/project-company/unbind"));
    }

    private Flux<FinanceProjectCompanyEntity> locked(String projectId) {
        var query = new QueryParamEntity();
        query.setForUpdate(true);
        query.orderBy("id");
        return relations
                .createQuery()
                .setParam(query)
                .where("projectId", projectId)
                .and("deleted", false)
                .fetch();
    }

    private Throwable duplicate(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.dao.DuplicateKeyException
                    || cause
                            instanceof org.hswebframework.ezorm.rdb.exception.DuplicateKeyException)
                return error("company_binding_conflict", 409);
        }
        return failure;
    }

    public record Row(FinanceProjectCompanyEntity relation, FinanceCompanyEntity company) {}
}
