package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.apache.commons.codec.digest.DigestUtils;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.calculation.AccountingCalendarRules;
import org.jetlinks.pro.property.finance.entity.AccountingCalendarOperationEntity;
import org.jetlinks.pro.property.finance.entity.AccountingPeriodEntity;
import org.jetlinks.pro.property.finance.entity.FinancialPeriodControlEntity;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 显式创建完整会计年度；查询保持只读，不因查不到日期而隐式生成自然月覆盖原自定义日历。 */
@Service
@RequiredArgsConstructor
public class AccountingCalendarService {
    private final FinanceScope scope;
    private final FinancialPeriodGate gate;
    private final AccountingCalendarRules rules;
    private final ReactiveRepository<AccountingPeriodEntity, String> periods;
    private final ReactiveRepository<AccountingCalendarOperationEntity, String> operations;

    public Mono<List<AccountingPeriodEntity>> year(String project, int year) {
        return scope.project(project, CrudAssetPermission.read)
                .then(TenantHolder.currentId().defaultIfEmpty(""))
                .flatMap(
                        tenant -> {
                            validateYear(year);
                            return rows(project, tenant, year, false);
                        });
    }

    public Mono<AccountingPeriodEntity> period(String project, String month) {
        return scope.project(project, CrudAssetPermission.read)
                .then(TenantHolder.currentId().defaultIfEmpty(""))
                .flatMap(
                        tenant -> {
                            if (month == null
                                    || !month.matches("[0-9]{4}-(0[1-9]|1[0-2])")
                                    || month.startsWith("0000"))
                                return Mono.error(error("accounting_calendar_invalid", 400));
                            return periods.createQuery()
                                    .where("projectId", project)
                                    .and("tenantId", tenant)
                                    .and("accountMonth", month)
                                    .and("deleted", false)
                                    .fetchOne()
                                    .switchIfEmpty(
                                            Mono.error(error("accounting_period_not_found", 404)));
                        });
    }

    @Transactional
    public Mono<List<AccountingPeriodEntity>> create(
            String project, AccountingCalendarRules.Input input) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(auth -> Mono.defer(() -> createAuthorized(project, input, auth)))
                .as(MonoTracer.create("property.finance.accounting-calendar.create"));
    }

    private Mono<List<AccountingPeriodEntity>> createAuthorized(
            String project, AccountingCalendarRules.Input input, Authentication auth) {
        var expected = rules.generate(input);
        // 所有创建者先串行于同一项目期间行，避免十二个月出现交叉创建或部分年度。
        return gate.lock(project)
                .flatMap(
                        control ->
                                rows(project, control.getTenantId(), input.year(), true)
                                        .flatMap(
                                                existing ->
                                                        restoreOrInsert(
                                                                project, control, input, expected,
                                                                existing, auth)));
    }

    private Mono<List<AccountingPeriodEntity>> restoreOrInsert(
            String project,
            FinancialPeriodControlEntity control,
            AccountingCalendarRules.Input input,
            List<AccountingCalendarRules.Period> expected,
            List<AccountingPeriodEntity> existing,
            Authentication auth) {
        if (!existing.isEmpty()) return restore(existing, input, expected);
        return gate.assertOpen(control, expected.get(0).start())
                .then(insert(project, control.getTenantId(), input, expected, auth));
    }

    private Mono<List<AccountingPeriodEntity>> restore(
            List<AccountingPeriodEntity> existing,
            AccountingCalendarRules.Input input,
            List<AccountingCalendarRules.Period> expected) {
        if (existing.size() != 12) return Mono.error(error("accounting_calendar_conflict", 409));
        int day = "NATURAL".equals(input.mode()) ? 1 : input.openingDay();
        for (int index = 0; index < 12; index++) {
            var row = existing.get(index);
            var dates = expected.get(index);
            if (Boolean.TRUE.equals(row.getDeleted())
                    || !dates.month().equals(row.getAccountMonth())
                    || !dates.start().toString().equals(row.getStartDate())
                    || !dates.end().toString().equals(row.getEndDate())
                    || !input.mode().equals(row.getMode())
                    || !Objects.equals(day, row.getOpeningDay()))
                return Mono.error(error("accounting_calendar_conflict", 409));
        }
        return Mono.just(existing);
    }

    private Mono<List<AccountingPeriodEntity>> insert(
            String project,
            String tenant,
            AccountingCalendarRules.Input input,
            List<AccountingCalendarRules.Period> expected,
            Authentication auth) {
        var rows = new ArrayList<AccountingPeriodEntity>(12);
        for (var dates : expected) {
            var row = new AccountingPeriodEntity();
            row.setId(
                    DigestUtils.sha256Hex(
                            JSON.toJSONString(List.of(tenant, project, dates.month()))));
            InvoiceCompanyService.init(row, auth);
            row.setTenantId(tenant);
            row.setProjectId(project);
            row.setAccountYear(input.year());
            row.setAccountMonth(dates.month());
            row.setStartDate(dates.start().toString());
            row.setEndDate(dates.end().toString());
            row.setMode(input.mode());
            row.setOpeningDay("NATURAL".equals(input.mode()) ? 1 : input.openingDay());
            row.setStatus("OPEN");
            rows.add(row);
        }
        var operation = new AccountingCalendarOperationEntity();
        operation.setId(UUID.randomUUID().toString());
        InvoiceCompanyService.init(operation, auth);
        operation.setTenantId(tenant);
        operation.setProjectId(project);
        operation.setAccountYear(input.year());
        operation.setAction("CREATE");
        // 显式字段构造审计，不依赖 record 的 JSON 自动发现，避免得到空对象丢失历史日期。
        operation.setDetailJson(
                JSON.toJSONString(
                        rows.stream()
                                .map(
                                        row ->
                                                Map.of(
                                                        "id",
                                                        row.getId(),
                                                        "month",
                                                        row.getAccountMonth(),
                                                        "start",
                                                        row.getStartDate(),
                                                        "end",
                                                        row.getEndDate(),
                                                        "mode",
                                                        row.getMode(),
                                                        "openingDay",
                                                        row.getOpeningDay(),
                                                        "status",
                                                        row.getStatus(),
                                                        "version",
                                                        row.getVersion()))
                                .toList()));
        return Flux.fromIterable(rows)
                .concatMap(periods::insert)
                .then(operations.insert(operation))
                .thenReturn(List.copyOf(rows));
    }

    Mono<List<AccountingPeriodEntity>> rows(
            String project, String tenant, int year, boolean includeDeleted) {
        var param = new QueryParamEntity();
        param.toQuery().orderByAsc("accountMonth");
        var query =
                periods.createQuery()
                        .setParam(param)
                        .where("projectId", project)
                        .and("tenantId", tenant)
                        .and("accountYear", year);
        if (!includeDeleted) query.and("deleted", false);
        return query.fetch()
                .take(13)
                .collectList()
                .flatMap(
                        rows ->
                                rows.size() > 12
                                        ? Mono.error(error("accounting_calendar_conflict", 409))
                                        : Mono.just(rows));
    }

    private void validateYear(int year) {
        if (year < 1 || year > 9999) throw error("accounting_calendar_invalid", 400);
    }
}
