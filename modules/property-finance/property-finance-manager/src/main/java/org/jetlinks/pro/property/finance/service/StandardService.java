package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.*;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.calculation.ChargeFormulaParser;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.standard.*;
import org.jetlinks.pro.property.finance.tax.TaxItemRules;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.*;

/** 项目收费标准使用授权项目边界、统一安全公式和可冻结的完整税务规则。 */
@Service
@RequiredArgsConstructor
public class StandardService {
    private final FinanceScope scope;
    private final ReactiveRepository<
                    org.jetlinks.pro.property.finance.entity.FundAccountEntity, String>
            fundAccounts;
    private final TaxItemService taxes;
    private final SubjectService subjects;
    private final SubjectCategoryService categories;
    private final FormulaVariableService variables;
    private final ChargeFormulaParser parser;
    private final ReactiveRepository<StandardEntity, String> standards;
    private final ReactiveRepository<ProjectSubjectEntity, String> projectSubjects;
    private final ReactiveRepository<SubjectEntity, String> subjectRepository;
    private final ReactiveRepository<StandardVariableReferenceEntity, String> references;
    private final ReactiveRepository<HouseSettingEntity, String> houseSettings;
    private final ReactiveRepository<PaymentAllocationEntity, String> paymentLines;
    private final ReactiveRepository<ActivityEntity, String> activities;

    public Mono<PagerResult<StandardView>> query(
            String projectId,
            QueryParamEntity input,
            String categoryId,
            String subjectId,
            String effectiveStatus,
            LocalDate businessDate) {
        LocalDate date = businessDate == null ? LocalDate.now() : businessDate;
        return scope.project(projectId, CrudAssetPermission.read)
                .then(
                        buildQuery(
                                projectId,
                                input,
                                categoryId,
                                subjectId,
                                effectiveStatus,
                                date,
                                false))
                .flatMap(
                        query ->
                                QueryHelper.transformPageResult(
                                        QueryHelper.queryPager(query, standards::createQuery),
                                        rows ->
                                                Flux.fromIterable(rows)
                                                        .map(row -> StandardView.of(row, date))
                                                        .collectList()));
    }

    public Mono<byte[]> export(
            String projectId,
            QueryParamEntity input,
            String categoryId,
            String subjectId,
            String effectiveStatus,
            LocalDate businessDate) {
        LocalDate date = businessDate == null ? LocalDate.now() : businessDate;
        return scope.project(projectId, CrudAssetPermission.read)
                .then(
                        buildQuery(
                                projectId,
                                input,
                                categoryId,
                                subjectId,
                                effectiveStatus,
                                date,
                                true))
                .flatMapMany(query -> standards.createQuery().setParam(query).fetch())
                .map(row -> StandardView.of(row, date))
                .collectList()
                .flatMap(
                        rows ->
                                Mono.fromCallable(() -> StandardWorkbook.write(rows))
                                        .subscribeOn(
                                                reactor.core.scheduler.Schedulers
                                                        .boundedElastic()));
    }

    private Mono<QueryParamEntity> buildQuery(
            String projectId,
            QueryParamEntity input,
            String categoryId,
            String subjectId,
            String status,
            LocalDate date,
            boolean export) {
        return categories
                .descendants(categoryId)
                .flatMap(
                        ids -> {
                            Mono<List<String>> matching =
                                    ids.isEmpty()
                                            ? Mono.just(List.of())
                                            : subjectRepository
                                                    .createQuery()
                                                    .in("categoryId", ids)
                                                    .and("deleted", false)
                                                    .fetch()
                                                    .map(SubjectEntity::getId)
                                                    .collectList();
                            return matching.map(
                                    subjectIds -> {
                                        var query = TaxItemService.bounded(input);
                                        if (export) query.noPaging();
                                        if (query.getSorts().isEmpty())
                                            query.toQuery()
                                                    .orderByDesc("createTime")
                                                    .orderByDesc("id");
                                        query.toNestQuery(
                                                nested -> {
                                                    nested.and("projectId", projectId)
                                                            .and("deleted", false);
                                                    if (subjectId != null && !subjectId.isBlank())
                                                        nested.and("subjectId", subjectId);
                                                    if (!ids.isEmpty())
                                                        nested.in(
                                                                "subjectId",
                                                                subjectIds.isEmpty()
                                                                        ? List.of("")
                                                                        : subjectIds);
                                                    if (status != null && !status.isBlank()) {
                                                        switch (status) {
                                                            case "NOT_STARTED" ->
                                                                    nested.gt(
                                                                            "executeDate",
                                                                            date.toString());
                                                            case "EXPIRED" ->
                                                                    nested.lt(
                                                                            "cancelDate",
                                                                            date.toString());
                                                            case "EFFECTIVE" -> {
                                                                nested.nest()
                                                                        .isNull("executeDate")
                                                                        .or()
                                                                        .lte(
                                                                                "executeDate",
                                                                                date.toString())
                                                                        .end();
                                                                nested.nest()
                                                                        .isNull("cancelDate")
                                                                        .or()
                                                                        .gte(
                                                                                "cancelDate",
                                                                                date.toString())
                                                                        .end();
                                                            }
                                                            default ->
                                                                    throw error(
                                                                            "standard_invalid",
                                                                            400);
                                                        }
                                                    }
                                                });
                                        return query;
                                    });
                        });
    }

    public Mono<StandardView> detail(String projectId, String id) {
        return scope.project(projectId, CrudAssetPermission.read)
                .then(standard(projectId, id, false))
                .map(row -> StandardView.of(row, LocalDate.now()));
    }

    public Mono<StandardRules.FormulaResult> validateFormula(
            String projectId, String expression, String display) {
        return scope.project(projectId, CrudAssetPermission.read)
                .thenMany(variables.definitions(projectId, false))
                .collectList()
                .map(
                        definitions ->
                                StandardRules.formula(parser, expression, display, definitions));
    }

    @Transactional
    public Mono<StandardView> create(String projectId, StandardInput input) {
        return scope.project(projectId, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(auth -> Mono.defer(() -> save(projectId, null, input, auth)))
                .onErrorMap(StandardService::duplicate)
                .as(MonoTracer.create("/property/finance/standard/create"));
    }

    @Transactional
    public Mono<StandardView> update(String projectId, String id, StandardInput input) {
        return scope.project(projectId, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(auth -> Mono.defer(() -> save(projectId, id, input, auth)))
                .onErrorMap(StandardService::duplicate)
                .as(MonoTracer.create("/property/finance/standard/update"));
    }

    private Mono<StandardView> save(
            String projectId, String id, StandardInput input, Authentication auth) {
        var next = StandardRules.validate(input);
        next.setProjectId(projectId);
        // 上游对象锁在标准之前；更换关联不需要锁旧上游对象，旧引用在CAS成功前仍可阻止删除。
        return taxes.related(projectId, next.getCompanyId(), true)
                .flatMap(
                        company ->
                                taxes.item(next.getTaxItemId(), true)
                                        .map(tax -> new TaxSource(company, tax)))
                .flatMap(
                        source -> {
                            if (!source.tax().getCompanyId().equals(next.getCompanyId())
                                    || !Boolean.TRUE.equals(source.tax().getEnabled()))
                                return Mono.error(error("tax_unavailable", 409));
                            next.setTaxSnapshotJson(taxSnapshot(source));
                            return requireSubject(projectId, next.getSubjectId());
                        })
                .doOnNext(
                        subject -> {
                            next.setSubjectName(subject.getName());
                            next.setCategoryId(subject.getCategoryId());
                        })
                .thenMany(variables.definitions(projectId, true))
                .collectList()
                .flatMap(
                        definitions -> {
                            var formula =
                                    StandardRules.formula(
                                            parser,
                                            input.getFormulaExpression(),
                                            input.getFormulaDisplay(),
                                            definitions);
                            next.setFormulaExpression(formula.formulaExpression());
                            next.setFormulaDisplay(formula.formulaDisplay());
                            next.setVariablesSnapshotJson(
                                    StandardSnapshots.variables(formula.variablesSnapshot()));
                            return id == null
                                    ? insert(next, auth, formula.variablesSnapshot())
                                    : standard(projectId, id, true)
                                            .flatMap(
                                                    old ->
                                                            replace(
                                                                    old,
                                                                    next,
                                                                    input.getVersion(),
                                                                    auth,
                                                                    formula.variablesSnapshot()));
                        })
                .map(saved -> StandardView.of(saved, LocalDate.now()));
    }

    private Mono<SubjectEntity> requireSubject(String projectId, String subjectId) {
        return subjects.subject(subjectId, true)
                .flatMap(
                        subject -> {
                            if (!Boolean.TRUE.equals(subject.getEnabled()))
                                return Mono.error(error("standard_subject_unavailable", 409));
                            var query = new QueryParamEntity();
                            query.setForUpdate(true);
                            return projectSubjects
                                    .createQuery()
                                    .setParam(query)
                                    .where("projectId", projectId)
                                    .and("subjectId", subjectId)
                                    .and("deleted", false)
                                    .fetchOne()
                                    .switchIfEmpty(
                                            Mono.error(error("standard_subject_unavailable", 409)))
                                    .thenReturn(subject);
                        });
    }

    private Mono<StandardEntity> insert(
            StandardEntity next, Authentication auth, List<VariableDefinition> used) {
        next.setId(UUID.randomUUID().toString());
        init(next, auth);
        next.setFormulaVersion(0L);
        return standards.insert(next).then(replaceReferences(next.getId(), used)).thenReturn(next);
    }

    private Mono<StandardEntity> replace(
            StandardEntity old,
            StandardEntity next,
            Long version,
            Authentication auth,
            List<VariableDefinition> used) {
        if (!Objects.equals(old.getVersion(), version))
            return Mono.error(error("version_conflict", 409));
        next.setFormulaVersion(old.getFormulaVersion() + 1);
        var update = audit(standards.createUpdate(), auth, version);
        var wrapper = new BeanWrapperImpl(next);
        for (var property : wrapper.getPropertyDescriptors()) {
            String field = property.getName();
            if (property.getWriteMethod() == null
                    || List.of(
                                    "id",
                                    "version",
                                    "deleted",
                                    "deletedTime",
                                    "createTime",
                                    "modifyTime",
                                    "modifierId",
                                    "modifierName")
                            .contains(field)) continue;
            Object value = wrapper.getPropertyValue(field);
            if (value == null) update.setNull(field);
            else update.set(field, value);
        }
        return update.where("id", old.getId())
                .and("projectId", old.getProjectId())
                .and("version", version)
                .and("deleted", false)
                .execute()
                .flatMap(SubjectService::changed)
                .then(replaceReferences(old.getId(), used))
                .then(standard(old.getProjectId(), old.getId(), false));
    }

    private Mono<Void> replaceReferences(String standardId, List<VariableDefinition> used) {
        var rows =
                used.stream()
                        .filter(variable -> variable.scopeType().equals("PROJECT"))
                        .map(
                                variable -> {
                                    var reference = new StandardVariableReferenceEntity();
                                    reference.setId(UUID.randomUUID().toString());
                                    reference.setStandardId(standardId);
                                    reference.setVariableId(variable.id());
                                    return reference;
                                })
                        .toList();
        return references
                .createDelete()
                .where("standardId", standardId)
                .execute()
                .then(
                        rows.isEmpty()
                                ? Mono.empty()
                                : references.insertBatch(Flux.just(rows)).then());
    }

    @Transactional
    public Mono<Void> delete(String projectId, String id, Long version) {
        return scope.project(projectId, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                standard(projectId, id, true)
                                        .flatMap(
                                                old -> {
                                                    if (!Objects.equals(old.getVersion(), version))
                                                        return Mono.error(
                                                                error("version_conflict", 409));
                                                    return activities
                                                            .createQuery()
                                                            .where("standardId", id)
                                                            .and("deleted", false)
                                                            .fetchOne()
                                                            .hasElement()
                                                            .flatMap(
                                                                    used ->
                                                                            used
                                                                                    ? Mono.error(
                                                                                            error(
                                                                                                    "standard_referenced",
                                                                                                    409))
                                                                                    : Mono.empty())
                                                            .then(
                                                                    paymentLines
                                                                            .createQuery()
                                                                            .where("standardId", id)
                                                                            .and(
                                                                                    "projectId",
                                                                                    projectId)
                                                                            .fetchOne()
                                                                            .hasElement()
                                                                            .flatMap(
                                                                                    used ->
                                                                                            used
                                                                                                    ? Mono
                                                                                                            .error(
                                                                                                                    error(
                                                                                                                            "standard_referenced",
                                                                                                                            409))
                                                                                                    : Mono
                                                                                                            .empty()))
                                                            .then(
                                                                    fundAccounts
                                                                            .createQuery()
                                                                            .where("standardId", id)
                                                                            .and("deleted", false)
                                                                            .fetchOne()
                                                                            .hasElement())
                                                            .flatMap(
                                                                    used ->
                                                                            used
                                                                                    ? Mono.error(
                                                                                            error(
                                                                                                    "standard_referenced",
                                                                                                    409))
                                                                                    : Mono.empty())
                                                            .then(
                                                                    houseSettings
                                                                            .createQuery()
                                                                            .where("standardId", id)
                                                                            .and("deleted", false)
                                                                            .fetchOne()
                                                                            .hasElement()
                                                                            .flatMap(
                                                                                    used ->
                                                                                            used
                                                                                                    ? Mono
                                                                                                            .error(
                                                                                                                    error(
                                                                                                                            "standard_referenced",
                                                                                                                            409))
                                                                                                    : Mono
                                                                                                            .empty()))
                                                            .then(
                                                                    audit(
                                                                                    standards
                                                                                            .createUpdate(),
                                                                                    auth,
                                                                                    version)
                                                                            .set("deleted", true)
                                                                            .set(
                                                                                    "deletedTime",
                                                                                    System
                                                                                            .currentTimeMillis())
                                                                            .where("id", id)
                                                                            .and(
                                                                                    "projectId",
                                                                                    projectId)
                                                                            .and("version", version)
                                                                            .and("deleted", false)
                                                                            .execute()
                                                                            .flatMap(
                                                                                    SubjectService
                                                                                            ::changed)
                                                                            .then(
                                                                                    references
                                                                                            .createDelete()
                                                                                            .where(
                                                                                                    "standardId",
                                                                                                    id)
                                                                                            .execute())
                                                                            .then());
                                                }))
                .as(MonoTracer.create("/property/finance/standard/delete"));
    }

    private Mono<StandardEntity> standard(String projectId, String id, boolean lock) {
        var query = new QueryParamEntity();
        query.setForUpdate(lock);
        return standards
                .createQuery()
                .setParam(query)
                .where("id", id)
                .and("projectId", projectId)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("standard_not_found", 404)));
    }

    public Mono<PagerResult<TaxItemEntity>> taxOptions(String projectId, QueryParamEntity input) {
        var query = TaxItemService.bounded(input);
        query.toNestQuery(nested -> nested.and("enabled", true));
        if (query.getSorts().isEmpty()) query.toQuery().orderByAsc("goodsName").orderByAsc("id");
        return taxes.query(projectId, query);
    }

    public Mono<Map<String, Object>> options(String projectId) {
        return scope.project(projectId, CrudAssetPermission.read)
                .thenMany(
                        projectSubjects
                                .createQuery()
                                .where("projectId", projectId)
                                .and("deleted", false)
                                .fetch())
                .map(ProjectSubjectEntity::getSubjectId)
                .collectList()
                .flatMap(
                        ids -> {
                            var subjectQuery = new QueryParamEntity();
                            subjectQuery.noPaging();
                            subjectQuery.orderBy("name");
                            subjectQuery.orderBy("id");
                            Mono<List<SubjectEntity>> issued =
                                    ids.isEmpty()
                                            ? Mono.just(List.of())
                                            : subjectRepository
                                                    .createQuery()
                                                    .setParam(subjectQuery)
                                                    .in("id", ids)
                                                    .and("deleted", false)
                                                    .and("enabled", true)
                                                    .fetch()
                                                    .collectList();
                            return categories
                                    .list()
                                    .collectList()
                                    .flatMap(
                                            categoryRows ->
                                                    issued.flatMap(
                                                            subjectRows ->
                                                                    variables
                                                                            .definitions(
                                                                                    projectId,
                                                                                    false)
                                                                            .filter(
                                                                                    VariableDefinition
                                                                                            ::enabled)
                                                                            .collectList()
                                                                            .map(
                                                                                    definitions -> {
                                                                                        var result =
                                                                                                StandardOptions
                                                                                                        .enums();
                                                                                        result.put(
                                                                                                "categories",
                                                                                                categoryRows);
                                                                                        result.put(
                                                                                                "subjects",
                                                                                                subjectRows);
                                                                                        result.put(
                                                                                                "variables",
                                                                                                definitions);
                                                                                        return result;
                                                                                    })));
                        });
    }

    private static String taxSnapshot(TaxSource source) {
        var tax = source.tax();
        var snapshot = new LinkedHashMap<String, Object>();
        var wrapper = new BeanWrapperImpl(tax);
        for (String field : TaxItemRules.FIELDS.keySet())
            snapshot.put(field, wrapper.getPropertyValue(field));
        snapshot.put("taxItemId", tax.getId());
        snapshot.put("companyId", source.company().getId());
        snapshot.put("companyName", source.company().getCompanyName());
        snapshot.put("sellerTaxNum", source.company().getTaxNumber());
        snapshot.put("taxItemVersion", tax.getVersion());
        snapshot.put("companyVersion", source.company().getVersion());
        snapshot.put("taxRate", tax.getTaxRate().toPlainString());
        snapshot.put("price", tax.getPrice() == null ? null : tax.getPrice().toPlainString());
        snapshot.put("includingTaxFlag", tax.getIncludingTaxFlag());
        snapshot.put("favouredPolicyFlag", tax.getFavouredPolicyFlag());
        return JSON.toJSONString(snapshot);
    }

    static Throwable duplicate(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof org.springframework.dao.DuplicateKeyException
                    || cause
                            instanceof org.hswebframework.ezorm.rdb.exception.DuplicateKeyException)
                return error("standard_duplicate", 409);
        return failure;
    }

    private record TaxSource(FinanceCompanyEntity company, TaxItemEntity tax) {}
}
