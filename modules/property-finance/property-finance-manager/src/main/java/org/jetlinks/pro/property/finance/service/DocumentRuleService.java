package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.*;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Mono;

import java.util.*;

/** 项目开票时效/重开规则；CAS和同事务日志防止并发静默覆盖。 */
@Service
@RequiredArgsConstructor
public class DocumentRuleService {
    private final FinanceScope scope;
    private final ReactiveRepository<DocumentRuleEntity, String> rules;
    private final ReactiveRepository<DocumentRuleLogEntity, String> logs;

    public Mono<View> get(String project) {
        return scope.project(project, CrudAssetPermission.read).then(effective(project, false));
    }

    Mono<View> effective(String project, boolean lock) {
        return row(project, lock)
                .map(DocumentRuleService::view)
                .defaultIfEmpty(new View(null, "NATURAL_YEAR", null, 0));
    }

    @Transactional
    public Mono<View> save(String project, Input input) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            validate(input);
                                            return row(project, true)
                                                    .map(Optional::of)
                                                    .defaultIfEmpty(Optional.empty())
                                                    .flatMap(
                                                            old ->
                                                                    persist(
                                                                            project,
                                                                            input,
                                                                            old.orElse(null),
                                                                            auth));
                                        }))
                .onErrorMap(DocumentRuleService::duplicate)
                .as(MonoTracer.create("property.finance.document.rule.save"));
    }

    private Mono<View> persist(
            String project, Input input, DocumentRuleEntity old, Authentication auth) {
        if (old == null && input.version() != null
                || old != null && !Objects.equals(old.getVersion(), input.version()))
            return Mono.error(error("version_conflict", 409));
        var next = new DocumentRuleEntity();
        next.setId(project);
        init(next, auth);
        next.setTimeLimitType(input.timeLimitType());
        next.setLimitDays("CUSTOM_DAYS".equals(input.timeLimitType()) ? input.limitDays() : null);
        next.setMaxReopenTimes(input.maxReopenTimes());
        Mono<Void> write;
        if (old == null) write = rules.insert(next).then();
        else {
            next.setVersion(old.getVersion() + 1);
            next.setCreateTime(old.getCreateTime());
            var update =
                    audit(rules.createUpdate(), auth, old.getVersion())
                            .set("timeLimitType", next.getTimeLimitType());
            // set(field, null)会忽略空值；切回自然年或不限重开必须真正清除旧配置。
            if (next.getLimitDays() == null) update.setNull("limitDays");
            else update.set("limitDays", next.getLimitDays());
            if (next.getMaxReopenTimes() == null) update.setNull("maxReopenTimes");
            else update.set("maxReopenTimes", next.getMaxReopenTimes());
            write =
                    update.where("id", project)
                            .and("version", old.getVersion())
                            .and("deleted", false)
                            .execute()
                            .flatMap(SubjectService::changed)
                            .then();
        }
        var log = new DocumentRuleLogEntity();
        log.setId(UUID.randomUUID().toString());
        init(log, auth);
        log.setProjectId(project);
        log.setReason(input.reason().trim());
        log.setBeforeSnapshot(old == null ? null : JSON.toJSONString(old));
        log.setAfterSnapshot(JSON.toJSONString(next));
        return write.then(logs.insert(log)).thenReturn(view(next));
    }

    private Mono<DocumentRuleEntity> row(String project, boolean lock) {
        var query = new QueryParamEntity();
        query.setForUpdate(lock);
        return rules.createQuery()
                .setParam(query)
                .where("id", project)
                .and("deleted", false)
                .fetchOne();
    }

    private static void validate(Input input) {
        if (input == null
                || !Set.of("NATURAL_YEAR", "CUSTOM_DAYS")
                        .contains(Objects.toString(input.timeLimitType(), ""))
                || "CUSTOM_DAYS".equals(input.timeLimitType())
                        && (input.limitDays() == null
                                || input.limitDays() < 1
                                || input.limitDays() > 36500)
                || input.maxReopenTimes() != null && input.maxReopenTimes() < 0
                || input.reason() == null
                || input.reason().isBlank()
                || input.reason().trim().length() > 500) throw error("document_rule_invalid", 400);
    }

    private static View view(DocumentRuleEntity row) {
        return new View(
                row.getVersion(),
                row.getTimeLimitType(),
                row.getLimitDays(),
                row.getMaxReopenTimes());
    }

    private static Throwable duplicate(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof org.springframework.dao.DuplicateKeyException
                    || cause
                            instanceof org.hswebframework.ezorm.rdb.exception.DuplicateKeyException)
                return error("version_conflict", 409);
        return failure;
    }

    public record Input(
            Long version,
            String timeLimitType,
            Integer limitDays,
            Integer maxReopenTimes,
            String reason) {}

    public record View(
            Long version, String timeLimitType, Integer limitDays, Integer maxReopenTimes) {}
}
