package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.property.finance.entity.*;
import org.springframework.stereotype.Component;

import reactor.core.publisher.*;

import java.util.Set;

/** 柜台收款共用的标准来源；公司锁后按科目、下发、标准顺序加锁。 */
@Component
@RequiredArgsConstructor
public class CollectionStandardSource {
    private final SubjectService subjects;
    private final ReactiveRepository<StandardEntity, String> standards;
    private final ReactiveRepository<ProjectSubjectEntity, String> issued;

    Mono<StandardEntity> current(String project, String id, String company) {
        // 本方法只读取；财务引用锁统一在公司锁之后获取，防止与标准维护形成反向锁序。
        return standards
                .createQuery()
                .where("id", id)
                .and("projectId", project)
                .and("companyId", company)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("setting_standard_unavailable", 409)))
                .flatMap(
                        standard ->
                                subjects.subject(standard.getSubjectId(), false)
                                        .filter(
                                                subject ->
                                                        Boolean.TRUE.equals(subject.getEnabled()))
                                        .switchIfEmpty(
                                                Mono.error(
                                                        error("setting_subject_unavailable", 409)))
                                        .then(
                                                issued.createQuery()
                                                        .where("projectId", project)
                                                        .and("subjectId", standard.getSubjectId())
                                                        .and("deleted", false)
                                                        .fetchOne()
                                                        .switchIfEmpty(
                                                                Mono.error(
                                                                        error(
                                                                                "setting_subject_unavailable",
                                                                                409))))
                                        .thenReturn(standard));
    }

    /** 标准变更先锁公司；调用方加锁后重读来源，使预览校验涵盖最新引用版本。 */
    Mono<Void> lock(String project, String company, Set<String> ids) {
        return Flux.fromIterable(ids)
                .concatMap(id -> current(project, id, company))
                .collectList()
                .flatMapMany(
                        rows ->
                                Flux.fromIterable(
                                                rows.stream()
                                                        .map(StandardEntity::getSubjectId)
                                                        .distinct()
                                                        .sorted()
                                                        .toList())
                                        .concatMap(
                                                id ->
                                                        subjects.subject(id, true)
                                                                .then(lockIssued(project, id)))
                                        .thenMany(
                                                Flux.fromIterable(rows)
                                                        .concatMap(
                                                                row ->
                                                                        lockStandard(
                                                                                project,
                                                                                row.getId()))))
                .then();
    }

    private Mono<ProjectSubjectEntity> lockIssued(String project, String subject) {
        var query = new QueryParamEntity();
        query.setForUpdate(true);
        return issued.createQuery()
                .setParam(query)
                .where("projectId", project)
                .and("subjectId", subject)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("setting_subject_unavailable", 409)));
    }

    private Mono<StandardEntity> lockStandard(String project, String id) {
        var query = new QueryParamEntity();
        query.setForUpdate(true);
        return standards
                .createQuery()
                .setParam(query)
                .where("projectId", project)
                .and("id", id)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("setting_standard_unavailable", 409)));
    }
}
