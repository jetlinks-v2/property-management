package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 历史按原冻结空间授权，列表整期先校验再分页，不能从total泄露不可见的历史批次。 */
@Service
@RequiredArgsConstructor
public class MonthClosingRecords {
    private final FinanceScope scope;
    private final MonthClosingSources sources;
    private final ReactiveRepository<MonthClosingOperationEntity, String> operations;
    private final ReactiveRepository<MonthClosingMemberEntity, String> members;
    private final ObjectMapper mapper;

    public record Detail(
            MonthClosingOperationEntity operation, MonthClosingService.Result result) {}

    public Mono<PagerResult<Detail>> query(String project, String month, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(TenantHolder.currentId().defaultIfEmpty(""))
                .flatMap(
                        tenant -> {
                            if (month == null
                                    || !month.matches("[0-9]{4}-(0[1-9]|1[0-2])")
                                    || month.startsWith("0000"))
                                return Mono.error(error("month_closing_invalid", 400));
                            var q = SubjectService.page(input);
                            q.setForUpdate(false);
                            q.toNestQuery(
                                    nested ->
                                            nested.and("projectId", project)
                                                    .and("tenantId", tenant)
                                                    .and("accountMonth", month)
                                                    .and("deleted", false));
                            if (q.getSorts().isEmpty())
                                q.toQuery().orderByDesc("createTime").orderByDesc("id");
                            return authorizePeriod(project, tenant, month)
                                    .then(
                                            QueryHelper.transformPageResult(
                                                    QueryHelper.queryPager(
                                                            q, operations::createQuery),
                                                    rows ->
                                                            Mono.just(
                                                                    rows.stream()
                                                                            .map(this::detail)
                                                                            .toList())));
                        });
    }

    public Mono<Detail> detail(String project, String id) {
        return scope.project(project, CrudAssetPermission.read)
                .then(TenantHolder.currentId().defaultIfEmpty(""))
                .flatMap(
                        tenant ->
                                require(project, tenant, id)
                                        .flatMap(row -> authorize(row).thenReturn(detail(row))));
    }

    public Mono<PagerResult<MonthClosingMemberEntity>> members(
            String project, String id, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(TenantHolder.currentId().defaultIfEmpty(""))
                .flatMap(
                        tenant ->
                                require(project, tenant, id)
                                        .flatMap(
                                                row -> {
                                                    var q = SubjectService.page(input);
                                                    q.setForUpdate(false);
                                                    q.toNestQuery(
                                                            nested ->
                                                                    nested.and("projectId", project)
                                                                            .and("tenantId", tenant)
                                                                            .and(
                                                                                    "operationId",
                                                                                    row
                                                                                            .getCloseOperationId())
                                                                            .and("deleted", false));
                                                    if (q.getSorts().isEmpty())
                                                        q.toQuery()
                                                                .orderByAsc("sourceType")
                                                                .orderByAsc("sourceId");
                                                    return authorize(row)
                                                            .then(
                                                                    QueryHelper.queryPager(
                                                                            q,
                                                                            members::createQuery));
                                                }));
    }

    private Mono<Void> authorizePeriod(String project, String tenant, String month) {
        return operations
                .createQuery()
                .setParam(MonthClosingSources.limit())
                .where("projectId", project)
                .and("tenantId", tenant)
                .and("accountMonth", month)
                .and("action", "CLOSE")
                .and("deleted", false)
                .fetch()
                .collectList()
                .flatMap(
                        rows -> {
                            if (rows.size() > MonthClosingSources.LIMIT)
                                return Mono.error(error("month_closing_source_limit", 409));
                            return Flux.fromIterable(rows).concatMap(this::authorize).then();
                        });
    }

    private Mono<Void> authorize(MonthClosingOperationEntity row) {
        if (row.getCloseOperationId() == null || row.getMemberCount() == null)
            return Mono.error(error("month_closing_source_invalid", 409));
        return sources.restore(
                        row.getProjectId(),
                        row.getTenantId(),
                        row.getCloseOperationId(),
                        row.getMemberCount(),
                        CrudAssetPermission.read)
                .then();
    }

    private Mono<MonthClosingOperationEntity> require(String project, String tenant, String id) {
        if (id == null || id.isBlank() || id.length() > 64)
            return Mono.error(error("month_closing_operation_not_found", 404));
        return operations
                .createQuery()
                .where("projectId", project)
                .and("tenantId", tenant)
                .and("id", id)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("month_closing_operation_not_found", 404)));
    }

    private Detail detail(MonthClosingOperationEntity row) {
        try {
            var result = mapper.readValue(row.getResultJson(), MonthClosingService.Result.class);
            if (result == null) throw error("month_closing_source_invalid", 409);
            return new Detail(row, result);
        } catch (JsonProcessingException invalid) {
            throw error("month_closing_source_invalid", 409);
        }
    }
}
