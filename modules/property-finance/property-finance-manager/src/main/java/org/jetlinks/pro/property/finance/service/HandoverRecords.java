package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.core.param.SqlTerm;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;

import reactor.core.publisher.*;

import java.util.*;

/** 所有候选和历史批次在分页前执行整单空间权限，不用分页后过滤伪造total。 */
@Service
@RequiredArgsConstructor
public class HandoverRecords {
    private final FinanceScope scope;
    private final DocumentQueryScope permissions;
    private final HandoverSources sources;
    private final ReactiveRepository<PaymentEntity, String> payments;
    private final ReactiveRepository<PaymentAllocationEntity, String> paymentLines;
    private final ReactiveRepository<RefundEntity, String> refunds;
    private final ReactiveRepository<RefundAllocationEntity, String> refundLines;
    private final ReactiveRepository<FundEntryEntity, String> entries;
    private final ReactiveRepository<HandoverEntity, String> batches;
    private final ReactiveRepository<HandoverLineEntity, String> lines;
    private final ReactiveRepository<HandoverSourceEntity, String> bindings;
    private final ReactiveRepository<HandoverOperationEntity, String> operations;

    public record Candidate(
            String type,
            String id,
            Long version,
            String occurredAt,
            String companyName,
            String customerName,
            String amount,
            String cashAmount,
            int lineCount) {}

    public record Detail(HandoverEntity batch, List<HandoverLineEntity> lines) {}

    public Mono<PagerResult<Candidate>> candidates(
            String project, String type, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.save)
                .then(TenantHolder.currentId().defaultIfEmpty(""))
                .flatMap(
                        tenant ->
                                Mono.defer(
                                        () -> {
                                            if (!Set.of("PAYMENT", "REFUND", "FUND").contains(type))
                                                return Mono.error(error("handover_invalid", 400));
                                            var q = query(input, project);
                                            q.toQuery().orderByDesc("createTime").orderByDesc("id");
                                            var claimQuery = new QueryParamEntity();
                                            claimQuery.setPaging(false);
                                            claimQuery
                                                    .toQuery()
                                                    .and("tenantId", tenant)
                                                    .and("projectId", project)
                                                    .and("sourceType", type)
                                                    .and("deleted", false)
                                                    .notNull("batchId");
                                            var claimed =
                                                    bindings.nativeQuery()
                                                            .select("sourceId")
                                                            .setParam(claimQuery)
                                                            .getSql();
                                            q.toQuery()
                                                    .accept(
                                                            SqlTerm.of(
                                                                    "id not in ("
                                                                            + claimed.getSql()
                                                                            + ")",
                                                                    claimed.getParameters()));
                                            return switch (type) {
                                                case "PAYMENT" ->
                                                        permissions
                                                                .allRelated(
                                                                        paymentLines,
                                                                        "paymentId",
                                                                        project,
                                                                        CrudAssetPermission.save)
                                                                .flatMap(
                                                                        term -> {
                                                                            q.toQuery()
                                                                                    .accept(term)
                                                                                    .and(
                                                                                            "status",
                                                                                            "SUCCEEDED");
                                                                            return pageCandidates(
                                                                                    project, type,
                                                                                    q, payments);
                                                                        });
                                                case "REFUND" ->
                                                        permissions
                                                                .allRelated(
                                                                        refundLines,
                                                                        "refundId",
                                                                        project,
                                                                        CrudAssetPermission.save)
                                                                .flatMap(
                                                                        term -> {
                                                                            q.toQuery()
                                                                                    .accept(term)
                                                                                    .and(
                                                                                            "status",
                                                                                            "SUCCEEDED");
                                                                            return pageCandidates(
                                                                                    project, type,
                                                                                    q, refunds);
                                                                        });
                                                default -> {
                                                    q.toQuery()
                                                            .isNull("paymentId")
                                                            .isNull("refundId");
                                                    yield permissions
                                                            .inject(q, CrudAssetPermission.save)
                                                            .flatMap(
                                                                    secured ->
                                                                            pageCandidates(
                                                                                    project, type,
                                                                                    secured,
                                                                                    entries));
                                                }
                                            };
                                        }));
    }

    private <T extends FinanceEntity> Mono<PagerResult<Candidate>> pageCandidates(
            String project, String type, QueryParamEntity q, ReactiveRepository<T, String> rows) {
        return QueryHelper.transformPageResult(
                QueryHelper.queryPager(q, rows::createQuery),
                page ->
                        Flux.fromIterable(page)
                                .concatMap(
                                        row ->
                                                sources.load(
                                                        project,
                                                        new HandoverSources.Item(
                                                                type,
                                                                row.getId(),
                                                                row.getVersion()),
                                                        false,
                                                        CrudAssetPermission.save))
                                .map(
                                        s -> {
                                            var l = s.lines().get(0);
                                            return new Candidate(
                                                    type,
                                                    s.id(),
                                                    s.version(),
                                                    l.getOccurredAt(),
                                                    l.getCompanyName(),
                                                    l.getCustomerName(),
                                                    HandoverSources.sum(s.lines(), false)
                                                            .toPlainString(),
                                                    HandoverSources.sum(s.lines(), true)
                                                            .toPlainString(),
                                                    s.lines().size());
                                        })
                                .collectList());
    }

    public Mono<PagerResult<HandoverEntity>> query(String project, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(TenantHolder.currentId().defaultIfEmpty(""))
                .flatMap(
                        tenant ->
                                permissions
                                        .allRelated(
                                                lines, "batchId", project, CrudAssetPermission.read)
                                        .flatMap(
                                                term -> {
                                                    var q = query(input, project);
                                                    q.toQuery()
                                                            .and("tenantId", tenant)
                                                            .accept(term)
                                                            .orderByDesc("createTime")
                                                            .orderByDesc("id");
                                                    return QueryHelper.queryPager(
                                                            q, batches::createQuery);
                                                }));
    }

    public Mono<Detail> detail(String project, String id) {
        return scope.project(project, CrudAssetPermission.read)
                .then(TenantHolder.currentId().defaultIfEmpty(""))
                .flatMap(
                        tenant ->
                                require(project, id, tenant, false)
                                        .flatMap(row -> detail(row, CrudAssetPermission.read)));
    }

    Mono<HandoverEntity> require(String project, String id, String tenant, boolean lock) {
        return batches.createQuery()
                .setParam(HandoverSources.param(lock))
                .where("projectId", project)
                .and("tenantId", tenant)
                .and("id", id)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("handover_missing", 404)));
    }

    Mono<Detail> detail(HandoverEntity row, CrudAssetPermission permission) {
        return lines.createQuery()
                .setParam(HandoverSources.limit())
                .where("projectId", row.getProjectId())
                .and("batchId", row.getId())
                .and("deleted", false)
                .fetch()
                .collectList()
                .flatMap(
                        data -> {
                            if (data.isEmpty() || data.size() > 10000)
                                return Mono.error(error("handover_source_invalid", 409));
                            return sources.authorize(data, permission)
                                    .thenReturn(new Detail(row, data));
                        });
    }

    public Mono<PagerResult<HandoverOperationEntity>> logs(
            String project, String id, QueryParamEntity input) {
        return detail(project, id)
                .then(
                        Mono.defer(
                                () -> {
                                    var q = query(input, project);
                                    q.toQuery().and("batchId", id).orderByDesc("createTime");
                                    return QueryHelper.queryPager(q, operations::createQuery);
                                }));
    }

    private static QueryParamEntity query(QueryParamEntity input, String project) {
        var q = SubjectService.page(input);
        q.setForUpdate(false);
        q.toNestQuery(n -> n.and("projectId", project).and("deleted", false));
        return q;
    }
}
