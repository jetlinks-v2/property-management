package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.*;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.subject.ArrearsConfigView;
import org.jetlinks.pro.property.finance.subject.ArrearsInput;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/** 欠费天数阈值排序且唯一；规则只配置通知等级，不改变逾期费计算。 */
@Service
@RequiredArgsConstructor
public class ArrearsService {
    private final FinanceScope scope;
    private final SubjectDictionaryService dictionaries;
    private final ReactiveRepository<ArrearsConfigEntity, String> configs;
    private final ReactiveRepository<ArrearsRuleEntity, String> rules;
    private final ReactiveRepository<SubjectEntity, String> subjects;

    public Mono<PagerResult<ArrearsConfigView>> query(QueryParamEntity input) {
        return scope.authenticated()
                .flatMap(
                        auth -> {
                            var query = SubjectService.page(input);
                            query.toNestQuery(nested -> nested.and("deleted", false));
                            return QueryHelper.queryPager(query, configs::createQuery)
                                    .flatMap(page -> withRules(page, query));
                        });
    }

    private Mono<PagerResult<ArrearsConfigView>> withRules(
            PagerResult<ArrearsConfigEntity> page, QueryParamEntity query) {
        if (page.getData().isEmpty())
            return Mono.just(PagerResult.of(page.getTotal(), List.of(), query));
        // 只批量读取本页配置的有效规则，避免逐条详情查询，也不展示已替换的历史阈值。
        var ids = page.getData().stream().map(ArrearsConfigEntity::getId).toList();
        var ruleQuery = new QueryParamEntity();
        ruleQuery.setPaging(false);
        ruleQuery.orderBy("overdueDays");
        return rules.createQuery()
                .setParam(ruleQuery)
                .in("configId", ids)
                .and("deleted", false)
                .fetch()
                .collect(java.util.stream.Collectors.groupingBy(ArrearsRuleEntity::getConfigId))
                .map(
                        values ->
                                PagerResult.of(
                                        page.getTotal(),
                                        page.getData().stream()
                                                .map(
                                                        row ->
                                                                ArrearsConfigView.of(
                                                                        row,
                                                                        values.getOrDefault(
                                                                                row.getId(),
                                                                                List.of())))
                                                .toList(),
                                        query));
    }

    public Flux<ArrearsConfigEntity> options() {
        var query = new QueryParamEntity();
        query.setPaging(false);
        query.orderBy("name");
        return scope.authenticated()
                .thenMany(configs.createQuery().setParam(query).where("deleted", false).fetch());
    }

    public Mono<Detail> detail(String id) {
        var query = new QueryParamEntity();
        query.setPaging(false);
        query.orderBy("overdueDays");
        return scope.authenticated()
                .then(config(id, false))
                .flatMap(
                        entity ->
                                rules.createQuery()
                                        .setParam(query)
                                        .where("configId", id)
                                        .and("deleted", false)
                                        .fetch()
                                        .collectList()
                                        .map(values -> new Detail(entity, values)));
    }

    @Transactional
    public Mono<Detail> create(ArrearsInput input) {
        return scope.authenticated()
                .flatMap(
                        auth ->
                                validate(input)
                                        .flatMap(
                                                normalized -> {
                                                    var entity = new ArrearsConfigEntity();
                                                    entity.setId(UUID.randomUUID().toString());
                                                    init(entity, auth);
                                                    entity.setName(input.name().trim());
                                                    entity.setNameKey(entity.getName());
                                                    entity.setRemark(input.remark());
                                                    return configs.insert(entity)
                                                            .then(
                                                                    replace(
                                                                            entity.getId(),
                                                                            normalized,
                                                                            auth))
                                                            .then(detail(entity.getId()));
                                                }))
                .onErrorMap(SubjectService::duplicate);
    }

    @Transactional
    public Mono<Detail> update(String id, ArrearsInput input) {
        return scope.authenticated()
                .flatMap(
                        auth ->
                                validate(input)
                                        .flatMap(
                                                normalized ->
                                                        config(id, true)
                                                                .flatMap(
                                                                        old -> {
                                                                            if (input.version()
                                                                                            == null
                                                                                    || !input.version()
                                                                                            .equals(
                                                                                                    old
                                                                                                            .getVersion()))
                                                                                return Mono.error(
                                                                                        error(
                                                                                                "version_conflict",
                                                                                                409));
                                                                            var update =
                                                                                    audit(
                                                                                                    configs
                                                                                                            .createUpdate(),
                                                                                                    auth,
                                                                                                    old
                                                                                                            .getVersion())
                                                                                            .set(
                                                                                                    "name",
                                                                                                    input.name()
                                                                                                            .trim())
                                                                                            .set(
                                                                                                    "nameKey",
                                                                                                    input.name()
                                                                                                            .trim());
                                                                            if (input.remark()
                                                                                    == null)
                                                                                update.setNull(
                                                                                        "remark");
                                                                            else
                                                                                update.set(
                                                                                        "remark",
                                                                                        input
                                                                                                .remark());
                                                                            return update.where(
                                                                                            "id",
                                                                                            id)
                                                                                    .and(
                                                                                            "version",
                                                                                            old
                                                                                                    .getVersion())
                                                                                    .execute()
                                                                                    .flatMap(
                                                                                            SubjectService
                                                                                                    ::changed)
                                                                                    .then(
                                                                                            replace(
                                                                                                    id,
                                                                                                    normalized,
                                                                                                    auth))
                                                                                    .then(
                                                                                            detail(
                                                                                                    id));
                                                                        })))
                .onErrorMap(SubjectService::duplicate);
    }

    @Transactional
    public Mono<Void> delete(String id, long version) {
        return scope.authenticated()
                .flatMap(
                        auth ->
                                config(id, true)
                                        .flatMap(
                                                old -> {
                                                    if (old.getVersion() != version)
                                                        return Mono.error(
                                                                error("version_conflict", 409));
                                                    return subjects.createQuery()
                                                            .where("arrearsConfigId", id)
                                                            .and("deleted", false)
                                                            .count()
                                                            .flatMap(SubjectService::unreferenced)
                                                            .then(
                                                                    audit(
                                                                                    configs
                                                                                            .createUpdate(),
                                                                                    auth,
                                                                                    version)
                                                                            .set("deleted", true)
                                                                            .setNull("nameKey")
                                                                            .set(
                                                                                    "deletedTime",
                                                                                    System
                                                                                            .currentTimeMillis())
                                                                            .where("id", id)
                                                                            .and("version", version)
                                                                            .execute())
                                                            .flatMap(SubjectService::changed)
                                                            .then(markRulesDeleted(id, auth));
                                                }));
    }

    /** 科目引用创建方同样持有配置行锁；拒绝把查询失败解释为无引用。 */
    Mono<ArrearsConfigEntity> config(String id, boolean lock) {
        var query = new QueryParamEntity();
        query.setForUpdate(lock);
        return configs.createQuery()
                .setParam(query)
                .where("id", id)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("arrears_not_found", 404)));
    }

    private Mono<List<ArrearsInput.Rule>> validate(ArrearsInput input) {
        return Mono.defer(
                () -> {
                    if (input == null
                            || input.name() == null
                            || input.name().isBlank()
                            || input.name().trim().length() > 100
                            || (input.remark() != null && input.remark().length() > 250)
                            || input.rules() == null
                            || input.rules().isEmpty()
                            || input.rules().size() > 100) throw error("arrears_invalid", 400);
                    var days = new HashSet<Integer>();
                    var normalized = new ArrayList<ArrearsInput.Rule>();
                    for (var rule : input.rules()) {
                        if (rule == null
                                || rule.overdueDays() == null
                                || rule.overdueDays() < 1
                                || !days.add(rule.overdueDays())
                                || rule.levelCode() == null
                                || rule.levelCode().isBlank()
                                || rule.levelCode().length() > 64)
                            throw error("arrears_invalid", 400);
                        normalized.add(
                                new ArrearsInput.Rule(
                                        null, rule.overdueDays(), rule.levelCode().trim()));
                    }
                    normalized.sort(Comparator.comparing(ArrearsInput.Rule::overdueDays));
                    return dictionaries
                            .options("arrears-level")
                            .map(
                                    options -> {
                                        if (normalized.stream()
                                                .anyMatch(
                                                        rule ->
                                                                options.stream()
                                                                        .noneMatch(
                                                                                option ->
                                                                                        option.value()
                                                                                                .equals(
                                                                                                        rule
                                                                                                                .levelCode()))))
                                            throw error("subject_dictionary_invalid", 400);
                                        return normalized;
                                    });
                });
    }

    private Mono<Void> replace(String id, List<ArrearsInput.Rule> values, Authentication auth) {
        return markRulesDeleted(id, auth)
                .thenMany(Flux.range(0, values.size()))
                .concatMap(
                        index -> {
                            var value = values.get(index);
                            var entity = new ArrearsRuleEntity();
                            entity.setId(UUID.randomUUID().toString());
                            init(entity, auth);
                            entity.setConfigId(id);
                            entity.setOverdueDays(value.overdueDays());
                            entity.setLevelCode(value.levelCode());
                            entity.setSortIndex(index + 1);
                            return rules.insert(entity);
                        })
                .then();
    }

    private Mono<Void> markRulesDeleted(String id, Authentication auth) {
        return rules.createQuery()
                .where("configId", id)
                .and("deleted", false)
                .fetch()
                .concatMap(
                        rule ->
                                audit(rules.createUpdate(), auth, rule.getVersion())
                                        .set("deleted", true)
                                        .set("deletedTime", System.currentTimeMillis())
                                        .where("id", rule.getId())
                                        .execute())
                .then();
    }

    public record Detail(ArrearsConfigEntity config, List<ArrearsRuleEntity> rules) {}
}
