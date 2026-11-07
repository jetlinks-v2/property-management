package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.apache.commons.codec.digest.DigestUtils;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Mono;

import java.util.*;

/** 自动月结只维护本项目调度，配置版本与会计期控制版本独立。 */
@Service
@RequiredArgsConstructor
public class MonthClosingScheduleService {
    private final FinanceScope scope;
    private final FinancialPeriodGate gate;
    private final ReactiveRepository<MonthClosingScheduleEntity, String> schedules;
    private final ReactiveRepository<MonthClosingScheduleLogEntity, String> logs;
    private final ReactiveRepository<MonthClosingAutomaticRunEntity, String> runs;
    static final String AUTHORITY_ERROR = "month_schedule_authority_invalid";

    public record Input(Long version, Boolean enabled, String mode, Integer day, String reason) {}

    public record View(Long version, boolean enabled, String mode, Integer day) {}

    public Mono<View> get(String project) {
        return scope.project(project, CrudAssetPermission.read)
                .then(TenantHolder.currentId().defaultIfEmpty(""))
                .flatMap(
                        tenant ->
                                row(project, tenant, false)
                                        .map(MonthClosingScheduleService::view)
                                        .defaultIfEmpty(new View(null, false, "MONTH_END", null)));
    }

    @Transactional
    public Mono<View> save(String project, Input input) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                TenantHolder.currentId()
                                        .defaultIfEmpty("")
                                        .flatMap(tenant -> save(project, tenant, input, auth)))
                .as(MonoTracer.create("property.finance.month-closing.schedule.save"));
    }

    private Mono<View> save(String project, String tenant, Input input, Authentication auth) {
        validate(input);
        if (!auth.hasPermission("property-finance-month-closing", "save")
                || input.enabled() && !mayExecute(auth))
            return Mono.error(error(AUTHORITY_ERROR, 403));
        // 编辑与任务均先锁已有计划；首次使用项目门闩串行化建档，不暴露通用upsert。
        return row(project, tenant, true)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(
                        existing ->
                                existing.isPresent()
                                        ? persist(existing.get(), input, auth, false)
                                        : gate.lock(project)
                                                .then(
                                                        row(project, tenant, true)
                                                                .map(Optional::of)
                                                                .defaultIfEmpty(Optional.empty()))
                                                .flatMap(
                                                        after ->
                                                                after.isPresent()
                                                                        ? persist(
                                                                                after.get(),
                                                                                input,
                                                                                auth,
                                                                                false)
                                                                        : persist(
                                                                                fresh(
                                                                                        project,
                                                                                        tenant,
                                                                                        auth),
                                                                                input,
                                                                                auth,
                                                                                true)));
    }

    private Mono<View> persist(
            MonthClosingScheduleEntity row, Input input, Authentication auth, boolean fresh) {
        if (fresh ? input.version() != null : !Objects.equals(input.version(), row.getVersion()))
            return Mono.error(error("version_conflict", 409));
        var log = new MonthClosingScheduleLogEntity();
        log.setId(UUID.randomUUID().toString());
        InvoiceCompanyService.init(log, auth);
        log.setTenantId(row.getTenantId());
        log.setProjectId(row.getProjectId());
        long version = fresh ? 0 : row.getVersion() + 1;
        log.setScheduleVersion(version);
        log.setEnabled(input.enabled());
        log.setMode(input.mode());
        log.setDay(input.day());
        log.setReason(input.reason().trim());
        Mono<String> snapshot =
                input.enabled()
                        ? FinanceJobAuthority.capture(auth, AUTHORITY_ERROR)
                        : Mono.just("");
        return snapshot.flatMap(
                        encoded -> {
                            if (fresh) {
                                row.setEnabled(input.enabled());
                                row.setMode(input.mode());
                                row.setDay(input.day());
                                row.setExecutorSnapshot(encoded);
                                row.setNextAttemptAt(0L);
                                return schedules.insert(row).then();
                            }
                            var update =
                                    InvoiceCompanyService.audit(
                                                    schedules.createUpdate(),
                                                    auth,
                                                    row.getVersion())
                                            .set("tenantId", row.getTenantId())
                                            .set("enabled", input.enabled())
                                            .set("mode", input.mode())
                                            .set("executorSnapshot", encoded)
                                            .set("nextAttemptAt", 0L);
                            // ORM 忽略普通 null 赋值；切回月末时须显式清除之前的指定日。
                            if (input.day() == null) update.setNull("day");
                            else update.set("day", input.day());
                            return update.where("id", row.getId())
                                    .and("version", row.getVersion())
                                    .and("deleted", false)
                                    .execute()
                                    .flatMap(SubjectService::changed);
                        })
                .then(logs.insert(log))
                .thenReturn(new View(version, input.enabled(), input.mode(), input.day()));
    }

    public Mono<PagerResult<MonthClosingAutomaticRunEntity>> history(
            String project, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(TenantHolder.currentId().defaultIfEmpty(""))
                .flatMap(
                        tenant -> {
                            var query = SubjectService.page(input);
                            query.setForUpdate(false);
                            query.toNestQuery(
                                    n ->
                                            n.and("projectId", project)
                                                    .and("tenantId", tenant)
                                                    .and("deleted", false));
                            if (query.getSorts().isEmpty())
                                query.toQuery().orderByDesc("createTime").orderByDesc("id");
                            return QueryHelper.queryPager(query, runs::createQuery);
                        });
    }

    Mono<MonthClosingScheduleEntity> row(String project, String tenant, boolean lock) {
        var query = new QueryParamEntity();
        query.setForUpdate(lock);
        return schedules
                .createQuery()
                .setParam(query)
                .where("projectId", project)
                .and("tenantId", tenant)
                .and("deleted", false)
                .fetchOne();
    }

    static boolean mayExecute(Authentication auth) {
        return auth.hasPermission("property-finance-month-closing", "save")
                && auth.hasPermission("property-finance-month-closing", "close");
    }

    private MonthClosingScheduleEntity fresh(String project, String tenant, Authentication auth) {
        var row = new MonthClosingScheduleEntity();
        row.setId(DigestUtils.sha256Hex(JSON.toJSONString(List.of(tenant, project))));
        InvoiceCompanyService.init(row, auth);
        row.setTenantId(tenant);
        row.setProjectId(project);
        return row;
    }

    private static View view(MonthClosingScheduleEntity row) {
        return new View(
                row.getVersion(),
                Boolean.TRUE.equals(row.getEnabled()),
                row.getMode(),
                row.getDay());
    }

    private void validate(Input input) {
        if (input == null
                || input.enabled() == null
                || input.version() != null && input.version() < 0
                || !Set.of("MONTH_END", "DAY").contains(Objects.toString(input.mode(), ""))
                || "MONTH_END".equals(input.mode()) && input.day() != null
                || "DAY".equals(input.mode())
                        && (input.day() == null || input.day() < 1 || input.day() > 28)
                || input.reason() == null
                || input.reason().isBlank()
                || input.reason().length() > 1000) throw error("month_schedule_invalid", 400);
    }
}
