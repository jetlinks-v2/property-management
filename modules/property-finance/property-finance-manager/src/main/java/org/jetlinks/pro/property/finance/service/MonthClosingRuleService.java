package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Mono;

import java.util.*;

/** 规则与结账共用项目期间门闩；CAS版本变化不允许覆盖期间边界。 */
@Service
@RequiredArgsConstructor
public class MonthClosingRuleService {
    private final FinanceScope scope;
    private final FinancialPeriodGate gate;
    private final ReactiveRepository<FinancialPeriodControlEntity, String> controls;
    private final ReactiveRepository<MonthClosingRuleOperationEntity, String> logs;

    public record Input(Long version, Boolean requireReceivableReview, String reason) {}

    public record View(Long version, boolean requireReceivableReview) {}

    public Mono<View> get(String project) {
        return scope.project(project, CrudAssetPermission.read)
                .then(tenant())
                .flatMap(
                        tenant ->
                                row(project, tenant)
                                        .map(this::view)
                                        .defaultIfEmpty(new View(null, false)));
    }

    @Transactional
    public Mono<View> save(String project, Input input) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                tenant().flatMap(
                                                tenant ->
                                                        Mono.defer(
                                                                () -> {
                                                                    validate(input);
                                                                    // 先只读识别首次保存；取得期间锁后再按真实行版本CAS，首次并发只允许一方成功。
                                                                    return row(project, tenant)
                                                                            .map(control -> true)
                                                                            .defaultIfEmpty(false)
                                                                            .flatMap(
                                                                                    existed ->
                                                                                            gate.lock(
                                                                                                            project)
                                                                                                    .flatMap(
                                                                                                            control -> {
                                                                                                                if (input
                                                                                                                                        .version()
                                                                                                                                == null
                                                                                                                        ? existed
                                                                                                                                || control
                                                                                                                                                .getVersion()
                                                                                                                                        != 0
                                                                                                                        : !Objects
                                                                                                                                .equals(
                                                                                                                                        input
                                                                                                                                                .version(),
                                                                                                                                        control
                                                                                                                                                .getVersion()))
                                                                                                                    return Mono
                                                                                                                            .error(
                                                                                                                                    error(
                                                                                                                                            "version_conflict",
                                                                                                                                            409));
                                                                                                                return persist(
                                                                                                                        control,
                                                                                                                        input,
                                                                                                                        auth);
                                                                                                            }));
                                                                })))
                .as(MonoTracer.create("property.finance.month-closing.rule.save"));
    }

    private Mono<View> persist(
            FinancialPeriodControlEntity control, Input input, Authentication auth) {
        var log = new MonthClosingRuleOperationEntity();
        log.setId(UUID.randomUUID().toString());
        InvoiceCompanyService.init(log, auth);
        log.setTenantId(control.getTenantId());
        log.setProjectId(control.getProjectId());
        log.setPreviousRequireReview(Boolean.TRUE.equals(control.getRequireReceivableReview()));
        log.setRequireReview(input.requireReceivableReview());
        log.setReason(input.reason().trim());
        return InvoiceCompanyService.audit(controls.createUpdate(), auth, control.getVersion())
                .set("tenantId", control.getTenantId())
                .set("requireReceivableReview", input.requireReceivableReview())
                .where("id", control.getId())
                .and("version", control.getVersion())
                .and("deleted", false)
                .execute()
                .flatMap(SubjectService::changed)
                .then(logs.insert(log))
                .thenReturn(new View(control.getVersion() + 1, input.requireReceivableReview()));
    }

    private Mono<FinancialPeriodControlEntity> row(String project, String tenant) {
        return controls.createQuery()
                .where("projectId", project)
                .and("tenantId", tenant)
                .and("deleted", false)
                .fetchOne();
    }

    private Mono<String> tenant() {
        return TenantHolder.currentId().defaultIfEmpty("");
    }

    private View view(FinancialPeriodControlEntity control) {
        return new View(
                control.getVersion(), Boolean.TRUE.equals(control.getRequireReceivableReview()));
    }

    private void validate(Input input) {
        if (input == null
                || input.version() != null && input.version() < 0
                || input.requireReceivableReview() == null
                || input.reason() == null
                || input.reason().isBlank()
                || input.reason().length() > 1000) throw error("month_closing_rule_invalid", 400);
    }
}
