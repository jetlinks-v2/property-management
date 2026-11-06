package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.apache.commons.codec.digest.DigestUtils;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.calculation.AccountingCalendarRules;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.*;

/** 正式月结依次关闭，反结从最后一期开始；来源、标记、边界和成功日志原子发布。 */
@Service
@RequiredArgsConstructor
public class MonthClosingService {
    private final FinanceScope scope;
    private final FinancialPeriodGate gate;
    private final AccountingCalendarService calendar;
    private final AccountingCalendarRules calendarRules;
    private final MonthClosingSources sources;
    private final ReactiveRepository<AccountingPeriodEntity, String> periods;
    private final ReactiveRepository<FinancialPeriodControlEntity, String> controls;
    private final ReactiveRepository<MonthClosingOperationEntity, String> operations;
    private final ObjectMapper mapper;

    public record Submission(String requestId, Long version, String token, String reason) {}

    public record Preview(
            String token,
            Long version,
            String month,
            String action,
            String amount,
            String cashAmount,
            int sourceCount,
            int receivableCount,
            int pendingReceivableCount,
            int unhandedCount,
            int pendingHandoverCount,
            boolean requireReceivableReview) {}

    public record Result(
            String operationId,
            String month,
            String status,
            Long version,
            String closedThrough,
            String amount,
            String cashAmount,
            int sourceCount,
            int receivableCount) {}

    private record Plan(
            AccountingPeriodEntity period,
            FinancialPeriodControlEntity control,
            List<MonthClosingSources.Member> members,
            Preview preview,
            MonthClosingOperationEntity originalClose) {}

    /** 只读预览不初始化目录或期间行；确认时在门闩内重新核对完整来源。 */
    public Mono<Preview> preview(String project, String month, String action, LocalDate today) {
        return scope.project(project, CrudAssetPermission.save)
                .then(tenant())
                .flatMap(
                        tenant -> {
                            validate(month, action, today);
                            return calendar.period(project, month)
                                    .flatMap(
                                            period ->
                                                    control(project, tenant)
                                                            .flatMap(
                                                                    control ->
                                                                            plan(
                                                                                    project, tenant,
                                                                                    period, control,
                                                                                    action,
                                                                                    today)));
                        })
                .map(Plan::preview)
                .as(MonoTracer.create("property.finance.month-closing.preview"));
    }

    Mono<Result> batchReplay(
            String project,
            String month,
            String action,
            String requestId,
            Long version,
            String reason) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                tenant().flatMap(
                                                tenant -> {
                                                    var id =
                                                            DigestUtils.sha256Hex(
                                                                    JSON.toJSONString(
                                                                            List.of(
                                                                                    tenant,
                                                                                    project,
                                                                                    auth.getUser()
                                                                                            .getId(),
                                                                                    requestId)));
                                                    return operations
                                                            .createQuery()
                                                            .where("id", id)
                                                            .and("tenantId", tenant)
                                                            .and("projectId", project)
                                                            .and("deleted", false)
                                                            .fetchOne()
                                                            .flatMap(
                                                                    row -> {
                                                                        var saved = result(row);
                                                                        if (!month.equals(
                                                                                        row
                                                                                                .getAccountMonth())
                                                                                || !action.equals(
                                                                                        row
                                                                                                .getAction())
                                                                                || !reason.trim()
                                                                                        .equals(
                                                                                                row
                                                                                                        .getReason())
                                                                                || saved.version()
                                                                                        != version
                                                                                                + 1)
                                                                            return Mono.error(
                                                                                    error(
                                                                                            "month_closing_request_conflict",
                                                                                            409));
                                                                        return sources.restore(
                                                                                        project,
                                                                                        tenant,
                                                                                        row.getId(),
                                                                                        row
                                                                                                .getMemberCount())
                                                                                .thenReturn(saved);
                                                                    });
                                                }));
    }

    /** 批量预检只跳过由调用方统一验证的顺序；权限、年度完整性、来源快照仍逐期核对。 */
    Mono<Preview> batchPreview(String project, String month, String action, LocalDate today) {
        return scope.project(project, CrudAssetPermission.save)
                .then(tenant())
                .flatMap(
                        tenant -> {
                            validate(month, action, today);
                            return calendar.period(project, month)
                                    .flatMap(
                                            period -> {
                                                if (!("CLOSE".equals(action) ? "OPEN" : "CLOSED")
                                                        .equals(period.getStatus()))
                                                    return Mono.error(
                                                            error("month_closing_state", 409));
                                                if ("CLOSE".equals(action)
                                                        && LocalDate.parse(period.getEndDate())
                                                                .isAfter(today))
                                                    return Mono.error(
                                                            error("month_closing_not_due", 409));
                                                return control(project, tenant)
                                                        .flatMap(
                                                                control ->
                                                                        validYear(
                                                                                        project,
                                                                                        tenant,
                                                                                        period)
                                                                                .then(
                                                                                        Mono.defer(
                                                                                                () ->
                                                                                                        "CLOSE"
                                                                                                                        .equals(
                                                                                                                                action)
                                                                                                                ? closePlan(
                                                                                                                        project,
                                                                                                                        tenant,
                                                                                                                        period,
                                                                                                                        control)
                                                                                                                : reopenPlan(
                                                                                                                        project,
                                                                                                                        tenant,
                                                                                                                        period,
                                                                                                                        control))));
                                            });
                        })
                .map(Plan::preview);
    }

    @Transactional
    public Mono<Result> submit(
            String project, String month, String action, Submission input, LocalDate today) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                tenant().flatMap(
                                                tenant ->
                                                        Mono.defer(
                                                                () -> {
                                                                    validate(month, action, today);
                                                                    validate(input);
                                                                    var id =
                                                                            DigestUtils.sha256Hex(
                                                                                    JSON
                                                                                            .toJSONString(
                                                                                                    List
                                                                                                            .of(
                                                                                                                    tenant,
                                                                                                                    project,
                                                                                                                    auth.getUser()
                                                                                                                            .getId(),
                                                                                                                    input
                                                                                                                            .requestId())));
                                                                    var fingerprint =
                                                                            DigestUtils.sha256Hex(
                                                                                    JSON
                                                                                            .toJSONString(
                                                                                                    List
                                                                                                            .of(
                                                                                                                    month,
                                                                                                                    action,
                                                                                                                    input
                                                                                                                            .version(),
                                                                                                                    input
                                                                                                                            .token(),
                                                                                                                    input.reason()
                                                                                                                            .trim())));
                                                                    var replay =
                                                                            replay(
                                                                                    project,
                                                                                    tenant,
                                                                                    id,
                                                                                    fingerprint);
                                                                    // 反结本身必须能打开已结期间，因此不经
                                                                    // assertOpen；先恢复成功，再锁期间后二查。
                                                                    return replay.switchIfEmpty(
                                                                            Mono.defer(
                                                                                    () ->
                                                                                            gate.lock(
                                                                                                            project)
                                                                                                    .flatMap(
                                                                                                            control ->
                                                                                                                    replay
                                                                                                                            .switchIfEmpty(
                                                                                                                                    Mono
                                                                                                                                            .defer(
                                                                                                                                                    () ->
                                                                                                                                                            lockedPeriod(
                                                                                                                                                                            project,
                                                                                                                                                                            tenant,
                                                                                                                                                                            month)
                                                                                                                                                                    .flatMap(
                                                                                                                                                                            period -> {
                                                                                                                                                                                if (!Objects
                                                                                                                                                                                        .equals(
                                                                                                                                                                                                period
                                                                                                                                                                                                        .getVersion(),
                                                                                                                                                                                                input
                                                                                                                                                                                                        .version()))
                                                                                                                                                                                    return Mono
                                                                                                                                                                                            .error(
                                                                                                                                                                                                    error(
                                                                                                                                                                                                            "version_conflict",
                                                                                                                                                                                                            409));
                                                                                                                                                                                return plan(
                                                                                                                                                                                                project,
                                                                                                                                                                                                tenant,
                                                                                                                                                                                                period,
                                                                                                                                                                                                control,
                                                                                                                                                                                                action,
                                                                                                                                                                                                today)
                                                                                                                                                                                        .flatMap(
                                                                                                                                                                                                plan ->
                                                                                                                                                                                                        publish(
                                                                                                                                                                                                                project,
                                                                                                                                                                                                                tenant,
                                                                                                                                                                                                                id,
                                                                                                                                                                                                                fingerprint,
                                                                                                                                                                                                                input,
                                                                                                                                                                                                                action,
                                                                                                                                                                                                                plan,
                                                                                                                                                                                                                auth,
                                                                                                                                                                                                                today));
                                                                                                                                                                            }))))));
                                                                })))
                .as(MonoTracer.create("property.finance.month-closing.submit"));
    }

    private Mono<Plan> plan(
            String project,
            String tenant,
            AccountingPeriodEntity period,
            FinancialPeriodControlEntity control,
            String action,
            LocalDate today) {
        return validYear(project, tenant, period)
                .then(sequence(project, tenant, period, control, action, today))
                .then(
                        Mono.defer(
                                () ->
                                        "CLOSE".equals(action)
                                                ? closePlan(project, tenant, period, control)
                                                : reopenPlan(project, tenant, period, control)));
    }

    private Mono<Plan> closePlan(
            String project,
            String tenant,
            AccountingPeriodEntity period,
            FinancialPeriodControlEntity control) {
        return sources.prepare(project, tenant, period)
                .map(
                        snapshot -> {
                            var prepared = snapshot.sources();
                            int pendingBatches =
                                    (int)
                                            snapshot.members().stream()
                                                    .filter(
                                                            m ->
                                                                    "HANDOVER".equals(m.type())
                                                                            && !"APPROVED"
                                                                                    .equals(
                                                                                            JSON.parseObject(
                                                                                                            m
                                                                                                                    .detail())
                                                                                                    .getString(
                                                                                                            "status")))
                                                    .count();
                            var token =
                                    token(
                                            period,
                                            control,
                                            "CLOSE",
                                            prepared.token(),
                                            snapshot.members());
                            var preview =
                                    new Preview(
                                            token,
                                            period.getVersion(),
                                            period.getAccountMonth(),
                                            "CLOSE",
                                            prepared.amount(),
                                            prepared.cashAmount(),
                                            prepared.sources().size(),
                                            snapshot.receivableCount(),
                                            snapshot.pendingReceivableCount(),
                                            prepared.unhandedCount(),
                                            Math.max(prepared.pendingReviewCount(), pendingBatches),
                                            Boolean.TRUE.equals(
                                                    control.getRequireReceivableReview()));
                            return new Plan(period, control, snapshot.members(), preview, null);
                        });
    }

    private Mono<Plan> reopenPlan(
            String project,
            String tenant,
            AccountingPeriodEntity period,
            FinancialPeriodControlEntity control) {
        return closeOperation(project, tenant, period)
                .flatMap(
                        original ->
                                sources.restore(
                                                project,
                                                tenant,
                                                original.getId(),
                                                original.getMemberCount())
                                        .map(
                                                members -> {
                                                    var saved = result(original);
                                                    var preview =
                                                            new Preview(
                                                                    token(
                                                                            period,
                                                                            control,
                                                                            "REOPEN",
                                                                            original.getId(),
                                                                            members),
                                                                    period.getVersion(),
                                                                    period.getAccountMonth(),
                                                                    "REOPEN",
                                                                    saved.amount(),
                                                                    saved.cashAmount(),
                                                                    saved.sourceCount(),
                                                                    saved.receivableCount(),
                                                                    0,
                                                                    0,
                                                                    0,
                                                                    Boolean.TRUE.equals(
                                                                            control
                                                                                    .getRequireReceivableReview()));
                                                    return new Plan(
                                                            period, control, members, preview,
                                                            original);
                                                }));
    }

    private Mono<Void> sequence(
            String project,
            String tenant,
            AccountingPeriodEntity period,
            FinancialPeriodControlEntity control,
            String action,
            LocalDate today) {
        if ("CLOSE".equals(action)) {
            if (!"OPEN".equals(period.getStatus()))
                return Mono.error(error("month_closing_state", 409));
            if (LocalDate.parse(period.getEndDate()).isAfter(today))
                return Mono.error(error("month_closing_not_due", 409));
            return periods.createQuery()
                    .where("projectId", project)
                    .and("tenantId", tenant)
                    .lt("accountMonth", period.getAccountMonth())
                    .not("status", "CLOSED")
                    .count()
                    .map(count -> count > 0)
                    .flatMap(
                            open -> {
                                if (open) return Mono.error(error("month_closing_sequence", 409));
                                return previous(project, tenant, period.getAccountMonth())
                                        .map(Optional::of)
                                        .defaultIfEmpty(Optional.empty())
                                        .flatMap(
                                                previous -> {
                                                    if (previous.isEmpty()) {
                                                        if (control.getClosedThrough() != null)
                                                            return Mono.error(
                                                                    error(
                                                                            "month_closing_sequence",
                                                                            409));
                                                    } else {
                                                        var row = previous.get();
                                                        if (Boolean.TRUE.equals(row.getDeleted())
                                                                || !"CLOSED".equals(row.getStatus())
                                                                || !Objects.equals(
                                                                        row.getEndDate(),
                                                                        control.getClosedThrough())
                                                                || !LocalDate.parse(
                                                                                row.getEndDate())
                                                                        .plusDays(1)
                                                                        .toString()
                                                                        .equals(
                                                                                period
                                                                                        .getStartDate()))
                                                            return Mono.error(
                                                                    error(
                                                                            "month_closing_sequence",
                                                                            409));
                                                    }
                                                    return Mono.empty();
                                                });
                            });
        }
        if (!"CLOSED".equals(period.getStatus())
                || !Objects.equals(period.getEndDate(), control.getClosedThrough()))
            return Mono.error(error("month_closing_state", 409));
        return periods.createQuery()
                .where("projectId", project)
                .and("tenantId", tenant)
                .gt("accountMonth", period.getAccountMonth())
                .and("status", "CLOSED")
                .count()
                .map(count -> count > 0)
                .flatMap(
                        later ->
                                later
                                        ? Mono.error(error("month_closing_sequence", 409))
                                        : Mono.empty());
    }

    private Mono<AccountingPeriodEntity> previous(String project, String tenant, String month) {
        var q = new QueryParamEntity();
        q.setPageSize(1);
        q.toQuery().orderByDesc("accountMonth");
        return periods.createQuery()
                .setParam(q)
                .where("projectId", project)
                .and("tenantId", tenant)
                .lt("accountMonth", month)
                .fetchOne();
    }

    private Mono<Void> validYear(String project, String tenant, AccountingPeriodEntity period) {
        return calendar.rows(project, tenant, period.getAccountYear(), false)
                .flatMap(
                        rows -> {
                            try {
                                if (rows.size() != 12
                                        || rows.stream()
                                                .anyMatch(
                                                        row ->
                                                                Boolean.TRUE.equals(
                                                                                row.getDeleted())
                                                                        || !Set.of("OPEN", "CLOSED")
                                                                                .contains(
                                                                                        Objects
                                                                                                .toString(
                                                                                                        row
                                                                                                                .getStatus(),
                                                                                                        ""))))
                                    throw error("accounting_calendar_conflict", 409);
                                calendarRules.validate(
                                        period.getAccountYear(),
                                        rows.stream()
                                                .map(
                                                        row ->
                                                                new AccountingCalendarRules.Period(
                                                                        row.getAccountMonth(),
                                                                        LocalDate.parse(
                                                                                row.getStartDate()),
                                                                        LocalDate.parse(
                                                                                row.getEndDate())))
                                                .toList());
                                return Mono.empty();
                            } catch (org.hswebframework.web.exception.BusinessException
                                    | java.time.DateTimeException invalid) {
                                return Mono.error(error("accounting_calendar_conflict", 409));
                            }
                        });
    }

    private Mono<Result> publish(
            String project,
            String tenant,
            String id,
            String fingerprint,
            Submission input,
            String action,
            Plan plan,
            Authentication auth,
            LocalDate today) {
        if (!plan.preview().token().equals(input.token()))
            return Mono.error(error("month_closing_preview_changed", 409));
        boolean close = "CLOSE".equals(action);
        if (close) {
            if (plan.preview().unhandedCount() > 0)
                return Mono.error(error("month_closing_unhanded", 409));
            if (plan.preview().pendingHandoverCount() > 0)
                return Mono.error(error("month_closing_unreviewed", 409));
            if (plan.preview().requireReceivableReview()
                    && plan.preview().pendingReceivableCount() > 0)
                return Mono.error(error("month_closing_receivable_review", 409));
        }
        var boundary =
                close
                        ? plan.period().getEndDate()
                        : plan.originalClose().getPreviousClosedThrough();
        var value =
                new Result(
                        id,
                        plan.period().getAccountMonth(),
                        close ? "CLOSED" : "OPEN",
                        plan.period().getVersion() + 1,
                        boundary,
                        plan.preview().amount(),
                        plan.preview().cashAmount(),
                        plan.preview().sourceCount(),
                        plan.preview().receivableCount());
        var operation = new MonthClosingOperationEntity();
        operation.setId(id);
        InvoiceCompanyService.init(operation, auth);
        operation.setTenantId(tenant);
        operation.setProjectId(project);
        operation.setAccountMonth(plan.period().getAccountMonth());
        operation.setAction(action);
        operation.setRequestFingerprint(fingerprint);
        operation.setResultJson(encode(value));
        operation.setPreviousClosedThrough(plan.control().getClosedThrough());
        operation.setCloseOperationId(close ? id : plan.originalClose().getId());
        operation.setMemberCount(plan.members().size());
        operation.setReason(input.reason().trim());
        return sources.flags(project, plan.members(), close, auth)
                .then(
                        close
                                ? sources.save(project, tenant, id, plan.members(), auth)
                                : Mono.empty())
                .then(updatePeriod(plan.period(), id, close, auth, today))
                .then(updateBoundary(plan.control(), boundary, auth))
                .then(operations.insert(operation))
                .thenReturn(value);
    }

    private Mono<Void> updatePeriod(
            AccountingPeriodEntity period,
            String id,
            boolean close,
            Authentication auth,
            LocalDate today) {
        var update =
                InvoiceCompanyService.audit(periods.createUpdate(), auth, period.getVersion())
                        .set("status", close ? "CLOSED" : "OPEN");
        if (close)
            update.set("closingOperationId", id)
                    .set("closedOn", today.toString())
                    .set("closedBy", auth.getUser().getId())
                    .set(
                            "closedName",
                            FinanceScope.operatorName(auth));
        else
            update.setNull("closingOperationId")
                    .setNull("closedOn")
                    .setNull("closedBy")
                    .setNull("closedName");
        return update.where("id", period.getId())
                .and("version", period.getVersion())
                .and("deleted", false)
                .execute()
                .flatMap(SubjectService::changed)
                .then();
    }

    private Mono<Void> updateBoundary(
            FinancialPeriodControlEntity control, String boundary, Authentication auth) {
        var update =
                InvoiceCompanyService.audit(controls.createUpdate(), auth, control.getVersion());
        if (boundary == null) update.setNull("closedThrough");
        else update.set("closedThrough", boundary);
        return update.where("id", control.getId())
                .and("version", control.getVersion())
                .and("deleted", false)
                .execute()
                .flatMap(SubjectService::changed)
                .then();
    }

    private Mono<Result> replay(String project, String tenant, String id, String fingerprint) {
        return operations
                .createQuery()
                .where("id", id)
                .and("projectId", project)
                .and("tenantId", tenant)
                .and("deleted", false)
                .fetchOne()
                .flatMap(
                        row -> {
                            if (!fingerprint.equals(row.getRequestFingerprint()))
                                return Mono.error(error("month_closing_request_conflict", 409));
                            var saved = result(row);
                            if (!Objects.equals(row.getAccountMonth(), saved.month())
                                    || !Objects.equals(row.getId(), saved.operationId())
                                    || !Set.of("CLOSE", "REOPEN").contains(row.getAction())
                                    || !("CLOSE".equals(row.getAction()) ? "CLOSED" : "OPEN")
                                            .equals(saved.status())
                                    || row.getMemberCount() == null
                                    || row.getCloseOperationId() == null)
                                return Mono.error(error("month_closing_source_invalid", 409));
                            return sources.restore(
                                            project,
                                            tenant,
                                            row.getCloseOperationId(),
                                            row.getMemberCount())
                                    .thenReturn(saved);
                        });
    }

    private Mono<MonthClosingOperationEntity> closeOperation(
            String project, String tenant, AccountingPeriodEntity period) {
        if (period.getClosingOperationId() == null)
            return Mono.error(error("month_closing_source_invalid", 409));
        return operations
                .createQuery()
                .where("id", period.getClosingOperationId())
                .and("projectId", project)
                .and("tenantId", tenant)
                .and("accountMonth", period.getAccountMonth())
                .and("action", "CLOSE")
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("month_closing_source_invalid", 409)))
                .flatMap(
                        original ->
                                previous(project, tenant, period.getAccountMonth())
                                        .map(Optional::of)
                                        .defaultIfEmpty(Optional.empty())
                                        .map(
                                                previous -> {
                                                    var saved = result(original);
                                                    String boundary =
                                                            previous.map(
                                                                            AccountingPeriodEntity
                                                                                    ::getEndDate)
                                                                    .orElse(null);
                                                    if (!Objects.equals(
                                                                    boundary,
                                                                    original
                                                                            .getPreviousClosedThrough())
                                                            || !Objects.equals(
                                                                    saved.closedThrough(),
                                                                    period.getEndDate())
                                                            || !Objects.equals(
                                                                    saved.operationId(),
                                                                    original.getId())
                                                            || !Objects.equals(
                                                                    saved.month(),
                                                                    period.getAccountMonth())
                                                            || !"CLOSED".equals(saved.status())
                                                            || original.getMemberCount() == null
                                                            || previous.filter(
                                                                            row ->
                                                                                    Boolean.TRUE
                                                                                                    .equals(
                                                                                                            row
                                                                                                                    .getDeleted())
                                                                                            || !"CLOSED"
                                                                                                    .equals(
                                                                                                            row
                                                                                                                    .getStatus()))
                                                                    .isPresent())
                                                        throw error(
                                                                "month_closing_source_invalid",
                                                                409);
                                                    return original;
                                                }));
    }

    private Mono<AccountingPeriodEntity> lockedPeriod(String project, String tenant, String month) {
        var q = new QueryParamEntity();
        q.setForUpdate(true);
        return periods.createQuery()
                .setParam(q)
                .where("projectId", project)
                .and("tenantId", tenant)
                .and("accountMonth", month)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("accounting_period_not_found", 404)));
    }

    private Mono<FinancialPeriodControlEntity> control(String project, String tenant) {
        return controls.createQuery()
                .where("projectId", project)
                .and("tenantId", tenant)
                .and("deleted", false)
                .fetchOne()
                .defaultIfEmpty(new FinancialPeriodControlEntity());
    }

    private String token(
            AccountingPeriodEntity period,
            FinancialPeriodControlEntity control,
            String action,
            String original,
            List<MonthClosingSources.Member> members) {
        return DigestUtils.sha256Hex(
                JSON.toJSONString(
                        Arrays.asList(
                                period.getId(),
                                period.getVersion(),
                                period.getStartDate(),
                                period.getEndDate(),
                                control.getClosedThrough(),
                                Boolean.TRUE.equals(control.getRequireReceivableReview()),
                                action,
                                original,
                                members.stream()
                                        .map(MonthClosingSources.Member::fingerprint)
                                        .toList())));
    }

    private Mono<String> tenant() {
        return TenantHolder.currentId().defaultIfEmpty("");
    }

    private String encode(Result value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw error("month_closing_source_invalid", 409);
        }
    }

    private Result result(MonthClosingOperationEntity row) {
        try {
            var value = mapper.readValue(row.getResultJson(), Result.class);
            if (value == null) throw error("month_closing_source_invalid", 409);
            return value;
        } catch (JsonProcessingException invalid) {
            throw error("month_closing_source_invalid", 409);
        }
    }

    private void validate(String month, String action, LocalDate today) {
        if (month == null
                || !month.matches("[0-9]{4}-(0[1-9]|1[0-2])")
                || month.startsWith("0000")
                || action == null
                || !Set.of("CLOSE", "REOPEN").contains(action)
                || today == null) throw error("month_closing_invalid", 400);
    }

    private void validate(Submission input) {
        if (input == null
                || input.requestId() == null
                || input.requestId().isBlank()
                || input.requestId().length() > 64
                || input.version() == null
                || input.version() < 0
                || input.token() == null
                || !input.token().matches("[0-9a-f]{64}")
                || input.reason() == null
                || input.reason().isBlank()
                || input.reason().length() > 1000) throw error("month_closing_invalid", 400);
    }
}
