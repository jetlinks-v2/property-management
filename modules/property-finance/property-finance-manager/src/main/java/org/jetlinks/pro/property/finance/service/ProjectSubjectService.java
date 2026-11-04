package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.*;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/** 项目下发是原子增量操作，重复提交保留现有关系，不移除未勾选项目的关系。 */
@Service
@RequiredArgsConstructor
public class ProjectSubjectService {
    private final FinanceScope scope;
    private final SubjectService subjects;
    private final SubjectCategoryService categories;
    private final ReactiveRepository<SubjectEntity, String> subjectRepository;
    private final ReactiveRepository<ProjectSubjectEntity, String> relations;

    public Mono<PagerResult<Row>> query(
            String projectId, QueryParamEntity input, String categoryId) {
        return scope.project(projectId, CrudAssetPermission.read)
                .thenMany(
                        relations
                                .createQuery()
                                .where("projectId", projectId)
                                .and("deleted", false)
                                .fetch())
                .collectMap(ProjectSubjectEntity::getSubjectId)
                .flatMap(
                        map ->
                                categories
                                        .descendants(categoryId)
                                        .flatMap(
                                                ids -> {
                                                    var query = SubjectService.page(input);
                                                    query.toNestQuery(
                                                            nested -> {
                                                                nested.and("deleted", false)
                                                                        .and(
                                                                                "id",
                                                                                "in",
                                                                                map.isEmpty()
                                                                                        ? List.of(
                                                                                                "__none__")
                                                                                        : map
                                                                                                .keySet());
                                                                if (!ids.isEmpty())
                                                                    nested.and(
                                                                            "categoryId",
                                                                            "in",
                                                                            ids);
                                                            });
                                                    return QueryHelper.queryPager(
                                                                    query,
                                                                    subjectRepository::createQuery)
                                                            .map(
                                                                    page ->
                                                                            PagerResult.of(
                                                                                    page.getTotal(),
                                                                                    page
                                                                                            .getData()
                                                                                            .stream()
                                                                                            .map(
                                                                                                    subject ->
                                                                                                            new Row(
                                                                                                                    map
                                                                                                                            .get(
                                                                                                                                    subject
                                                                                                                                            .getId()),
                                                                                                                    subject))
                                                                                            .toList(),
                                                                                    query));
                                                }));
    }

    public Mono<Row> detail(String projectId, String id) {
        return scope.project(projectId, CrudAssetPermission.read)
                .then(relation(projectId, id, false))
                .flatMap(
                        relation ->
                                subjects.subject(relation.getSubjectId(), false)
                                        .map(subject -> new Row(relation, subject)));
    }

    @Transactional
    public Mono<IssueResult> issue(IssueInput input) {
        return scope.authenticated()
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            if (input == null
                                                    || input.subjectIds() == null
                                                    || input.projectIds() == null
                                                    || input.subjectIds().isEmpty()
                                                    || input.projectIds().isEmpty()
                                                    || input.subjectIds().size() > 200
                                                    || input.projectIds().size() > 100
                                                    || input.subjectIds().stream()
                                                            .anyMatch(
                                                                    id ->
                                                                            id == null
                                                                                    || id.isBlank())
                                                    || input.projectIds().stream()
                                                            .anyMatch(
                                                                    id ->
                                                                            id == null
                                                                                    || id
                                                                                            .isBlank()))
                                                return Mono.error(
                                                        error("subject_issue_invalid", 400));
                                            var projectIds = new TreeSet<>(input.projectIds());
                                            var subjectIds = new TreeSet<>(input.subjectIds());
                                            // 先核验全部项目，再按稳定科目顺序加行锁，防止越权部分落库和并发重复下发。
                                            return Flux.fromIterable(projectIds)
                                                    .concatMap(
                                                            id ->
                                                                    scope.project(
                                                                            id,
                                                                            CrudAssetPermission
                                                                                    .save))
                                                    .thenMany(Flux.fromIterable(subjectIds))
                                                    .concatMap(id -> subjects.subject(id, true))
                                                    .collectList()
                                                    .flatMap(
                                                            locked ->
                                                                    Flux.fromIterable(locked)
                                                                            .concatMap(
                                                                                    subject ->
                                                                                            Flux
                                                                                                    .fromIterable(
                                                                                                            projectIds)
                                                                                                    .concatMap(
                                                                                                            project ->
                                                                                                                    bind(
                                                                                                                            project,
                                                                                                                            subject
                                                                                                                                    .getId(),
                                                                                                                            auth)))
                                                                            .collectList()
                                                                            .map(
                                                                                    created ->
                                                                                            new IssueResult(
                                                                                                    created
                                                                                                            .stream()
                                                                                                            .filter(
                                                                                                                    Boolean
                                                                                                                            ::booleanValue)
                                                                                                            .count(),
                                                                                                    created
                                                                                                            .stream()
                                                                                                            .filter(
                                                                                                                    value ->
                                                                                                                            !value)
                                                                                                            .count())));
                                        }))
                .onErrorMap(SubjectService::duplicate)
                .as(MonoTracer.create("/property/finance/subject/issue"));
    }

    private Mono<Boolean> bind(String projectId, String subjectId, Authentication auth) {
        return relations
                .createQuery()
                .where("projectId", projectId)
                .and("subjectId", subjectId)
                .fetchOne()
                .flatMap(
                        old -> {
                            if (!old.getDeleted()) return Mono.just(false);
                            return audit(relations.createUpdate(), auth, old.getVersion())
                                    .set("deleted", false)
                                    .setNull("deletedTime")
                                    .where("id", old.getId())
                                    .and("version", old.getVersion())
                                    .execute()
                                    .flatMap(SubjectService::changed)
                                    .thenReturn(true);
                        })
                .switchIfEmpty(
                        Mono.defer(
                                () -> {
                                    var relation = new ProjectSubjectEntity();
                                    relation.setId(UUID.randomUUID().toString());
                                    init(relation, auth);
                                    relation.setProjectId(projectId);
                                    relation.setSubjectId(subjectId);
                                    return relations.insert(relation).thenReturn(true);
                                }));
    }

    @Transactional
    public Mono<Void> delete(String projectId, String id, long version) {
        return scope.project(projectId, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                relation(projectId, id, false)
                                        .flatMap(
                                                selected ->
                                                        subjects.subject(
                                                                        selected.getSubjectId(),
                                                                        true)
                                                                .then(
                                                                        relation(
                                                                                projectId, id,
                                                                                true)))
                                        .flatMap(
                                                selected -> {
                                                    if (selected.getVersion() != version)
                                                        return Mono.error(
                                                                error("version_conflict", 409));
                                                    // 后续收费标准必须在此真实查询其引用，引用写入方同样锁定此稳定项目关系。
                                                    return audit(
                                                                    relations.createUpdate(),
                                                                    auth,
                                                                    version)
                                                            .set("deleted", true)
                                                            .set(
                                                                    "deletedTime",
                                                                    System.currentTimeMillis())
                                                            .where("id", id)
                                                            .and("projectId", projectId)
                                                            .and("version", version)
                                                            .and("deleted", false)
                                                            .execute()
                                                            .flatMap(SubjectService::changed);
                                                }))
                .as(MonoTracer.create("/property/finance/project-subject/delete"));
    }

    Mono<ProjectSubjectEntity> relation(String projectId, String id, boolean lock) {
        var query = new QueryParamEntity();
        query.setForUpdate(lock);
        return relations
                .createQuery()
                .setParam(query)
                .where("id", id)
                .and("projectId", projectId)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("project_subject_not_found", 404)));
    }

    public record IssueInput(List<String> subjectIds, List<String> projectIds) {}

    public record IssueResult(long created, long retained) {}

    public record Row(ProjectSubjectEntity relation, SubjectEntity subject) {}
}
