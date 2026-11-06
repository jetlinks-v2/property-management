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
    private final ReactiveRepository<DocumentEntity, String> documents;
    private final ReactiveRepository<DocumentLineEntity, String> documentLines;

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

    public record DetailQuery(
            Integer pageIndex,
            Integer pageSize,
            String handed,
            String spaceId,
            String customerName,
            String subjectName,
            String occurredStart,
            String occurredEnd) {}

    public record DetailRow(
            String id,
            String type,
            String sourceId,
            Long version,
            HandoverLineEntity line,
            String batchId,
            String status,
            String closingDate) {}

    /** 整单授权后才展开明细；达到上限显式拒绝，禁止分页后静默丢失原单。 */
    @org.springframework.transaction.annotation.Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Mono<List<DetailRow>> detailRows(String project, DetailQuery input) {
        if (input == null
                || (input.handed() != null && !Set.of("ALL", "YES", "NO").contains(input.handed())))
            return Mono.error(error("handover_invalid", 400));
        try {
            if (input.occurredStart() != null && !input.occurredStart().isBlank())
                java.time.LocalDate.parse(input.occurredStart());
            if (input.occurredEnd() != null && !input.occurredEnd().isBlank())
                java.time.LocalDate.parse(input.occurredEnd());
        } catch (java.time.DateTimeException error) {
            return Mono.error(error("handover_invalid", 400));
        }
        return scope.project(project, CrudAssetPermission.read)
                .then(TenantHolder.currentId().defaultIfEmpty(""))
                .flatMap(
                        tenant -> {
                            var q = HandoverSources.limit();
                            q.toQuery()
                                    .and("projectId", project)
                                    .and("deleted", false)
                                    .and("status", "SUCCEEDED");
                            var f = HandoverSources.limit();
                            f.toQuery()
                                    .and("projectId", project)
                                    .and("deleted", false)
                                    .isNull("paymentId")
                                    .isNull("refundId");
                            dateRange(f, "occurredAt", input);
                            var items =
                                    Flux.concat(
                                                    permissions
                                                            .allRelated(
                                                                    paymentLines,
                                                                    "paymentId",
                                                                    project,
                                                                    CrudAssetPermission.read)
                                                            .flatMapMany(
                                                                    term -> {
                                                                        var secured = q.clone();
                                                                        dateRange(
                                                                                secured, "paidAt",
                                                                                input);
                                                                        secured.toQuery()
                                                                                .accept(term);
                                                                        return payments.createQuery()
                                                                                .setParam(secured)
                                                                                .fetch();
                                                                    })
                                                            .map(
                                                                    row ->
                                                                            new HandoverSources
                                                                                    .Item(
                                                                                    "PAYMENT",
                                                                                    row.getId(),
                                                                                    row
                                                                                            .getVersion())),
                                                    permissions
                                                            .allRelated(
                                                                    refundLines,
                                                                    "refundId",
                                                                    project,
                                                                    CrudAssetPermission.read)
                                                            .flatMapMany(
                                                                    term -> {
                                                                        var secured = q.clone();
                                                                        dateRange(
                                                                                secured,
                                                                                "refundedAt",
                                                                                input);
                                                                        secured.toQuery()
                                                                                .accept(term);
                                                                        return refunds.createQuery()
                                                                                .setParam(secured)
                                                                                .fetch();
                                                                    })
                                                            .map(
                                                                    row ->
                                                                            new HandoverSources
                                                                                    .Item(
                                                                                    "REFUND",
                                                                                    row.getId(),
                                                                                    row
                                                                                            .getVersion())),
                                                    permissions
                                                            .inject(
                                                                    f,
                                                                    entries,
                                                                    CrudAssetPermission.read)
                                                            .flatMapMany(
                                                                    secured ->
                                                                            entries.createQuery()
                                                                                    .setParam(
                                                                                            secured)
                                                                                    .fetch())
                                                            .map(
                                                                    row ->
                                                                            new HandoverSources
                                                                                    .Item(
                                                                                    "FUND",
                                                                                    row.getId(),
                                                                                    row
                                                                                            .getVersion())))
                                            .take(10001)
                                            .collectList();
                            return items.flatMap(
                                    all -> {
                                        if (all.size() > 10000)
                                            return Mono.error(error("report_export_limit", 400));
                                        return Flux.fromIterable(all)
                                                .concatMap(
                                                        item ->
                                                                sources.load(
                                                                                project,
                                                                                item,
                                                                                false,
                                                                                CrudAssetPermission
                                                                                        .read)
                                                                        .flatMapMany(
                                                                                source ->
                                                                                        bindings.createQuery()
                                                                                                .where(
                                                                                                        "projectId",
                                                                                                        project)
                                                                                                .and(
                                                                                                        "tenantId",
                                                                                                        tenant)
                                                                                                .and(
                                                                                                        "sourceType",
                                                                                                        item
                                                                                                                .type())
                                                                                                .and(
                                                                                                        "sourceId",
                                                                                                        item
                                                                                                                .id())
                                                                                                .and(
                                                                                                        "deleted",
                                                                                                        false)
                                                                                                .notNull(
                                                                                                        "batchId")
                                                                                                .fetchOne()
                                                                                                .flatMap(
                                                                                                        binding ->
                                                                                                                require(
                                                                                                                        project,
                                                                                                                        binding
                                                                                                                                .getBatchId(),
                                                                                                                        tenant,
                                                                                                                        false))
                                                                                                .map(
                                                                                                        Optional
                                                                                                                ::of)
                                                                                                .defaultIfEmpty(
                                                                                                        Optional
                                                                                                                .empty())
                                                                                                .flatMapMany(
                                                                                                        batch ->
                                                                                                                Flux
                                                                                                                        .fromIterable(
                                                                                                                                source
                                                                                                                                        .lines())
                                                                                                                        .map(
                                                                                                                                line ->
                                                                                                                                        new DetailRow(
                                                                                                                                                item
                                                                                                                                                                .type()
                                                                                                                                                        + ":"
                                                                                                                                                        + line
                                                                                                                                                                .getOriginalLineId(),
                                                                                                                                                item
                                                                                                                                                        .type(),
                                                                                                                                                item
                                                                                                                                                        .id(),
                                                                                                                                                item
                                                                                                                                                        .version(),
                                                                                                                                                line,
                                                                                                                                                batch.map(
                                                                                                                                                                HandoverEntity
                                                                                                                                                                        ::getId)
                                                                                                                                                        .orElse(
                                                                                                                                                                null),
                                                                                                                                                batch.map(
                                                                                                                                                                HandoverEntity
                                                                                                                                                                        ::getStatus)
                                                                                                                                                        .orElse(
                                                                                                                                                                null),
                                                                                                                                                batch.map(
                                                                                                                                                                HandoverEntity
                                                                                                                                                                        ::getClosingDate)
                                                                                                                                                        .orElse(
                                                                                                                                                                null))))))
                                                .filter(row -> matches(row, input))
                                                .take(10001)
                                                .collectList()
                                                .map(
                                                        rows -> {
                                                            if (rows.size() > 10000)
                                                                throw error(
                                                                        "report_export_limit", 400);
                                                            return rows.stream()
                                                                    .sorted(
                                                                            Comparator.comparing(
                                                                                            (DetailRow
                                                                                                            r) ->
                                                                                                    r.line()
                                                                                                            .getOccurredAt())
                                                                                    .reversed()
                                                                                    .thenComparing(
                                                                                            DetailRow
                                                                                                    ::id))
                                                                    .toList();
                                                        });
                                    });
                        })
                .flatMap(rows -> receiptNumbers(project, rows));
    }

    private static void dateRange(QueryParamEntity query, String column, DetailQuery input) {
        if (input.occurredStart() != null && !input.occurredStart().isBlank())
            query.toQuery().gte(column, input.occurredStart());
        if (input.occurredEnd() != null && !input.occurredEnd().isBlank())
            query.toQuery()
                    .lt(
                            column,
                            java.time.LocalDate.parse(input.occurredEnd()).plusDays(1).toString());
    }

    private Mono<List<DetailRow>> receiptNumbers(String project, List<DetailRow> rows) {
        var keys = new HashMap<String, List<HandoverLineEntity>>();
        for (var row : rows) {
            var line = row.line();
            String type =
                    "PAYMENT".equals(row.type())
                            ? (line.getReceivableId() == null ? "TEMP_PAYMENT" : "RECEIVABLE")
                            : "FUND".equals(row.type()) ? "FUND_TOPUP" : null;
            if (type != null)
                keys.computeIfAbsent(
                                type
                                        + ":"
                                        + (line.getReceivableId() == null
                                                ? row.sourceId()
                                                : line.getReceivableId()),
                                ignored -> new ArrayList<>())
                        .add(line);
        }
        return permissions
                .allRelated(documentLines, "documentId", project, CrudAssetPermission.read)
                .flatMap(
                        term -> {
                            var query = HandoverSources.limit();
                            query.toQuery()
                                    .and("projectId", project)
                                    .and("deleted", false)
                                    .and("documentType", "RECEIPT")
                                    .accept(term);
                            return documents
                                    .createQuery()
                                    .setParam(query)
                                    .fetch()
                                    .collectList()
                                    .flatMap(
                                            receipts -> {
                                                if (receipts.size() > 10000)
                                                    return Mono.error(
                                                            error("report_export_limit", 400));
                                                var numbers = new HashMap<String, String>();
                                                receipts.forEach(
                                                        receipt ->
                                                                numbers.put(
                                                                        receipt.getId(),
                                                                        receipt
                                                                                .getDocumentNumber()));
                                                if (numbers.isEmpty() || keys.isEmpty())
                                                    return Mono.just(rows);
                                                var detailQuery = HandoverSources.limit();
                                                detailQuery
                                                        .toQuery()
                                                        .and("projectId", project)
                                                        .and("deleted", false)
                                                        .in("documentId", numbers.keySet());
                                                return documentLines
                                                        .createQuery()
                                                        .setParam(detailQuery)
                                                        .fetch()
                                                        .collectList()
                                                        .map(
                                                                details -> {
                                                                    if (details.size() > 10000)
                                                                        throw error(
                                                                                "report_export_limit",
                                                                                400);
                                                                    var matched =
                                                                            new HashMap<
                                                                                    HandoverLineEntity,
                                                                                    Set<String>>();
                                                                    for (var detail : details)
                                                                        for (var line :
                                                                                keys.getOrDefault(
                                                                                        detail
                                                                                                        .getSourceType()
                                                                                                + ":"
                                                                                                + detail
                                                                                                        .getSourceId(),
                                                                                        List.of()))
                                                                            matched.computeIfAbsent(
                                                                                            line,
                                                                                            ignored ->
                                                                                                    new TreeSet<>())
                                                                                    .add(
                                                                                            numbers
                                                                                                    .get(
                                                                                                            detail
                                                                                                                    .getDocumentId()));
                                                                    matched.forEach(
                                                                            (line, values) ->
                                                                                    line
                                                                                            .setReceiptNumbers(
                                                                                                    String
                                                                                                            .join(
                                                                                                                    ", ",
                                                                                                                    values)));
                                                                    return rows;
                                                                });
                                            });
                        });
    }

    @org.springframework.transaction.annotation.Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Mono<PagerResult<DetailRow>> details(String project, DetailQuery input) {
        return detailRows(project, input)
                .map(
                        rows -> {
                            var page = new QueryParamEntity();
                            page.setPageIndex(
                                    Math.max(0, input.pageIndex() == null ? 0 : input.pageIndex()));
                            page.setPageSize(
                                    Math.max(
                                            1,
                                            Math.min(
                                                    200,
                                                    input.pageSize() == null
                                                            ? 12
                                                            : input.pageSize())));
                            int from =
                                    (int)
                                            Math.min(
                                                    (long) page.getPageIndex() * page.getPageSize(),
                                                    rows.size());
                            return PagerResult.of(
                                    rows.size(),
                                    rows.subList(
                                            from, Math.min(from + page.getPageSize(), rows.size())),
                                    page);
                        });
    }

    private static boolean matches(DetailRow row, DetailQuery q) {
        var line = row.line();
        return (!"YES".equals(q.handed()) || row.batchId() != null)
                && (!"NO".equals(q.handed()) || row.batchId() == null)
                && (q.spaceId() == null
                        || q.spaceId().isBlank()
                        || q.spaceId().equals(line.getSpaceId()))
                && contains(line.getCustomerName(), q.customerName())
                && contains(line.getSubjectName(), q.subjectName())
                && (q.occurredStart() == null
                        || q.occurredStart().isBlank()
                        || line.getOccurredAt().compareTo(q.occurredStart()) >= 0)
                && (q.occurredEnd() == null
                        || q.occurredEnd().isBlank()
                        || line.getOccurredAt().substring(0, 10).compareTo(q.occurredEnd()) <= 0);
    }

    private static boolean contains(String value, String filter) {
        return filter == null || filter.isBlank() || (value != null && value.contains(filter));
    }

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
                                                            .inject(
                                                                    q,
                                                                    entries,
                                                                    CrudAssetPermission.save)
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
                                                    return QueryHelper.transformPageResult(
                                                            QueryHelper.queryPager(
                                                                    q, batches::createQuery),
                                                            rows ->
                                                                    Flux.fromIterable(rows)
                                                                            .concatMap(
                                                                                    row ->
                                                                                            detail(
                                                                                                            row,
                                                                                                            CrudAssetPermission
                                                                                                                    .read)
                                                                                                    .map(
                                                                                                            detail ->
                                                                                                                    row))
                                                                            .collectList());
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
                            row.setLineCount(data.size());
                            row.setOccurredStart(
                                    data.stream()
                                            .map(HandoverLineEntity::getOccurredAt)
                                            .filter(Objects::nonNull)
                                            .min(String::compareTo)
                                            .orElse(null));
                            row.setOccurredEnd(
                                    data.stream()
                                            .map(HandoverLineEntity::getOccurredAt)
                                            .filter(Objects::nonNull)
                                            .max(String::compareTo)
                                            .orElse(null));
                            row.setCalcStartDate(
                                    data.stream()
                                            .map(HandoverLineEntity::getCalcStartDate)
                                            .filter(Objects::nonNull)
                                            .min(String::compareTo)
                                            .orElse(null));
                            row.setCalcEndDate(
                                    data.stream()
                                            .map(HandoverLineEntity::getCalcEndDate)
                                            .filter(Objects::nonNull)
                                            .max(String::compareTo)
                                            .orElse(null));
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
