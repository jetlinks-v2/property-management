package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.*;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.standard.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/** 项目变量定义采用稳定身份和CAS；系统变量来自只读注册表。 */
@Service
@RequiredArgsConstructor
public class FormulaVariableService {
    private final FinanceScope scope;
    private final ReactiveRepository<FormulaVariableEntity, String> variables;
    private final ReactiveRepository<StandardVariableReferenceEntity, String> references;

    public Flux<VariableDefinition> list(String projectId) {
        return scope.project(projectId, CrudAssetPermission.read)
                .thenMany(definitions(projectId, false));
    }

    Flux<VariableDefinition> definitions(String projectId, boolean lock) {
        var query = new QueryParamEntity();
        query.noPaging();
        query.setForUpdate(lock);
        query.orderBy("id");
        return Flux.concat(
                Flux.fromIterable(VariableDefinition.SYSTEM),
                variables
                        .createQuery()
                        .setParam(query)
                        .where("projectId", projectId)
                        .and("deleted", false)
                        .fetch()
                        .map(VariableDefinition::of));
    }

    @Transactional
    public Mono<VariableDefinition> create(String projectId, VariableInput input) {
        return scope.project(projectId, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            var entity = validate(input);
                                            entity.setId(UUID.randomUUID().toString());
                                            entity.setProjectId(projectId);
                                            init(entity, auth);
                                            return variables
                                                    .insert(entity)
                                                    .thenReturn(VariableDefinition.of(entity));
                                        }))
                .onErrorMap(FormulaVariableService::duplicate);
    }

    @Transactional
    public Mono<VariableDefinition> update(String projectId, String id, VariableInput input) {
        return scope.project(projectId, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            var next = validate(input);
                                            return variable(projectId, id)
                                                    .flatMap(
                                                            old ->
                                                                    update(
                                                                            old,
                                                                            next,
                                                                            input.version(),
                                                                            auth));
                                        }))
                .onErrorMap(FormulaVariableService::duplicate);
    }

    private Mono<VariableDefinition> update(
            FormulaVariableEntity old,
            FormulaVariableEntity next,
            Long version,
            Authentication auth) {
        if (!Objects.equals(old.getVersion(), version))
            return Mono.error(error("version_conflict", 409));
        var update =
                audit(variables.createUpdate(), auth, version)
                        .set("variableCode", next.getVariableCode())
                        .set("variableName", next.getVariableName())
                        .set("nameKey", next.getNameKey())
                        .set("sort", next.getSort())
                        .set("enabled", next.getEnabled());
        if (next.getDescription() == null) update.setNull("description");
        else update.set("description", next.getDescription());
        return update.where("id", old.getId())
                .and("version", version)
                .and("deleted", false)
                .execute()
                .flatMap(SubjectService::changed)
                .then(variables.findById(old.getId()))
                .map(VariableDefinition::of);
    }

    @Transactional
    public Mono<Void> delete(String projectId, String id, Long version) {
        return scope.project(projectId, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                variable(projectId, id)
                                        .flatMap(
                                                old -> {
                                                    if (!Objects.equals(old.getVersion(), version))
                                                        return Mono.error(
                                                                error("version_conflict", 409));
                                                    return references
                                                            .createQuery()
                                                            .where("variableId", id)
                                                            .count()
                                                            .flatMap(
                                                                    count -> {
                                                                        if (count > 0)
                                                                            return Mono.error(
                                                                                    error(
                                                                                            "variable_referenced",
                                                                                            409));
                                                                        return audit(
                                                                                        variables
                                                                                                .createUpdate(),
                                                                                        auth,
                                                                                        version)
                                                                                .set(
                                                                                        "deleted",
                                                                                        true)
                                                                                .set(
                                                                                        "deletedTime",
                                                                                        System
                                                                                                .currentTimeMillis())
                                                                                .where("id", id)
                                                                                .and(
                                                                                        "version",
                                                                                        version)
                                                                                .and(
                                                                                        "deleted",
                                                                                        false)
                                                                                .execute()
                                                                                .flatMap(
                                                                                        SubjectService
                                                                                                ::changed);
                                                                    });
                                                }));
    }

    private Mono<FormulaVariableEntity> variable(String projectId, String id) {
        var query = new QueryParamEntity();
        query.setForUpdate(true);
        return variables
                .createQuery()
                .setParam(query)
                .where("id", id)
                .and("projectId", projectId)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("variable_not_found", 404)));
    }

    private static FormulaVariableEntity validate(VariableInput input) {
        if (input == null) throw error("variable_invalid", 400);
        var entity = new FormulaVariableEntity();
        entity.setVariableCode(StandardRules.text(input.variableCode(), 64, true));
        entity.setVariableName(StandardRules.text(input.variableName(), 100, true));
        entity.setNameKey(entity.getVariableName().toLowerCase(Locale.ROOT));
        entity.setDescription(StandardRules.text(input.description(), 300, false));
        entity.setSort(input.sort() == null ? 100 : input.sort());
        entity.setEnabled(input.enabled() == null || input.enabled());
        if (!entity.getVariableCode().matches("[A-Z][A-Z0-9_]{1,63}")
                || entity.getVariableName().matches(".*[\\[\\]{}].*")
                || VariableDefinition.SYSTEM.stream()
                        .anyMatch(
                                variable ->
                                        variable.variableCode().equals(entity.getVariableCode())
                                                || variable.variableName()
                                                        .equalsIgnoreCase(
                                                                entity.getVariableName())))
            throw error("variable_invalid", 400);
        return entity;
    }

    static Throwable duplicate(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.dao.DuplicateKeyException
                    || cause
                            instanceof org.hswebframework.ezorm.rdb.exception.DuplicateKeyException)
                return error("variable_duplicate", 409);
        }
        return failure;
    }
}
