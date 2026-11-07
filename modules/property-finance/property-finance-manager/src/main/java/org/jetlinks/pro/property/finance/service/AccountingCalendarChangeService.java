package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.calculation.AccountingCalendarRules;
import org.jetlinks.pro.property.finance.entity.*;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/** 整年验证并共同发布相邻期边界；期间锁内核对既有账务归属，CAS和审计失败全部回滚。 */
@Service
@RequiredArgsConstructor
public class AccountingCalendarChangeService {
    private final FinanceScope scope;
    private final FinancialPeriodGate gate;
    private final AccountingCalendarRules rules;
    private final AccountingCalendarService calendar;
    private final AccountingCalendarUsage usage;
    private final ReactiveRepository<AccountingPeriodEntity, String> periods;
    private final ReactiveRepository<AccountingCalendarOperationEntity, String> operations;

    public record Item(String month, Long version, String start, String end) {}

    public record Input(List<Item> items, String reason) {}

    @Transactional
    public Mono<List<AccountingPeriodEntity>> edit(String project, int year, Input input) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () ->
                                                editAuthorized(
                                                        project,
                                                        year,
                                                        normalize(year, input),
                                                        auth)))
                .as(MonoTracer.create("property.finance.accounting-calendar.edit"));
    }

    private Mono<List<AccountingPeriodEntity>> editAuthorized(
            String project, int year, Input input, Authentication auth) {
        return gate.lock(project)
                .flatMap(
                        control ->
                                calendar.rows(project, control.getTenantId(), year, true)
                                        .flatMap(
                                                before ->
                                                        prepareAndPublish(
                                                                project, year, control, input,
                                                                before, auth)));
    }

    private Mono<List<AccountingPeriodEntity>> prepareAndPublish(
            String project,
            int year,
            FinancialPeriodControlEntity control,
            Input input,
            List<AccountingPeriodEntity> before,
            Authentication auth) {
        if (before.size() != 12
                || before.stream().anyMatch(row -> Boolean.TRUE.equals(row.getDeleted())))
            return Mono.error(error("accounting_calendar_conflict", 409));
        var oldDates = existingDates(year, before);
        var dates = dates(input);
        var changed = new ArrayList<Integer>();
        for (int index = 0; index < 12; index++) {
            if (!Objects.equals(before.get(index).getVersion(), input.items().get(index).version()))
                return Mono.error(error("version_conflict", 409));
            if (!oldDates.get(index).equals(dates.get(index))) {
                if (!"OPEN".equals(before.get(index).getStatus()))
                    return Mono.error(error("accounting_calendar_closed", 409));
                changed.add(index);
            }
        }
        if (changed.isEmpty()) return Mono.error(error("accounting_calendar_no_change", 400));
        return Flux.fromIterable(changed)
                .concatMap(
                        index ->
                                gate.assertOpen(
                                        control,
                                        oldDates.get(index)
                                                        .start()
                                                        .isBefore(dates.get(index).start())
                                                ? oldDates.get(index).start()
                                                : dates.get(index).start()))
                .then(usage.assertUnused(project, before, dates))
                .then(publish(project, year, control.getTenantId(), input, before, changed, auth));
    }

    private Mono<List<AccountingPeriodEntity>> publish(
            String project,
            int year,
            String tenant,
            Input input,
            List<AccountingPeriodEntity> before,
            List<Integer> changed,
            Authentication auth) {
        var after =
                before.stream()
                        .map(
                                row -> {
                                    var copy = new AccountingPeriodEntity();
                                    BeanUtils.copyProperties(row, copy);
                                    return copy;
                                })
                        .toList();
        return Flux.fromIterable(changed)
                .concatMap(
                        index ->
                                update(
                                        before.get(index),
                                        after.get(index),
                                        input.items().get(index),
                                        auth))
                .then(
                        Mono.defer(
                                () ->
                                        audit(
                                                project,
                                                year,
                                                tenant,
                                                input.reason(),
                                                before,
                                                after,
                                                auth)))
                .thenReturn(after);
    }

    private Mono<Void> update(
            AccountingPeriodEntity before,
            AccountingPeriodEntity after,
            Item dates,
            Authentication auth) {
        after.setStartDate(dates.start());
        after.setEndDate(dates.end());
        after.setMode("MANUAL");
        after.setVersion(before.getVersion() + 1);
        after.setModifyTime(System.currentTimeMillis());
        after.setModifierId(auth.getUser().getId());
        after.setModifierName(FinanceScope.operatorName(auth));
        return InvoiceCompanyService.audit(periods.createUpdate(), auth, before.getVersion())
                .set("tenantId", before.getTenantId())
                .set("modifyTime", after.getModifyTime())
                .set("startDate", after.getStartDate())
                .set("endDate", after.getEndDate())
                .set("mode", "MANUAL")
                .where("id", before.getId())
                .and("version", before.getVersion())
                .and("deleted", false)
                .execute()
                .flatMap(SubjectService::changed)
                .then();
    }

    private Mono<Void> audit(
            String project,
            int year,
            String tenant,
            String reason,
            List<AccountingPeriodEntity> before,
            List<AccountingPeriodEntity> after,
            Authentication auth) {
        var operation = new AccountingCalendarOperationEntity();
        operation.setId(UUID.randomUUID().toString());
        InvoiceCompanyService.init(operation, auth);
        operation.setTenantId(tenant);
        operation.setProjectId(project);
        operation.setAccountYear(year);
        operation.setAction("EDIT");
        operation.setDetailJson(
                JSON.toJSONString(
                        Map.of(
                                "reason",
                                reason,
                                "before",
                                snapshot(before),
                                "after",
                                snapshot(after))));
        return operations.insert(operation).then();
    }

    private List<Map<String, Object>> snapshot(List<AccountingPeriodEntity> periods) {
        return periods.stream()
                .map(
                        row ->
                                Map.<String, Object>of(
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
                                        "version",
                                        row.getVersion()))
                .toList();
    }

    private List<AccountingCalendarRules.Period> existingDates(
            int year, List<AccountingPeriodEntity> before) {
        try {
            var dates =
                    before.stream()
                            .map(
                                    row ->
                                            new AccountingCalendarRules.Period(
                                                    row.getAccountMonth(),
                                                    LocalDate.parse(row.getStartDate()),
                                                    LocalDate.parse(row.getEndDate())))
                            .toList();
            rules.validate(year, dates);
            return dates;
        } catch (DateTimeParseException | BusinessException invalidCalendar) {
            throw error("accounting_calendar_conflict", 409);
        }
    }

    private Input normalize(int year, Input input) {
        if (input == null
                || input.items() == null
                || input.items().size() != 12
                || input.reason() == null
                || input.reason().isBlank()
                || input.reason().length() > 1000) throw error("accounting_calendar_invalid", 400);
        for (var item : input.items())
            if (item == null
                    || item.version() == null
                    || item.version() < 0
                    || item.start() == null
                    || item.end() == null) throw error("accounting_calendar_invalid", 400);
        rules.validate(year, dates(input));
        return new Input(List.copyOf(input.items()), input.reason().trim());
    }

    private List<AccountingCalendarRules.Period> dates(Input input) {
        try {
            return input.items().stream()
                    .map(
                            item ->
                                    new AccountingCalendarRules.Period(
                                            item.month(),
                                            LocalDate.parse(item.start()),
                                            LocalDate.parse(item.end())))
                    .toList();
        } catch (DateTimeParseException invalidDate) {
            throw error("accounting_calendar_invalid", 400);
        }
    }
}
