package org.jetlinks.pro.property.finance.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.core.param.SqlTerm;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.document.DocumentLedgerRow;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/** 独立票据历史按整张票据分页；所有关联房屋均有权后才显示金额、总数及操作日志。 */
@Service
@RequiredArgsConstructor
public class DocumentRecordService {
    private final FinanceScope scope;
    private final DocumentQueryScope visibility;
    private final DocumentStore store;
    private final ReactiveRepository<DocumentEntity, String> documents;
    private final ReactiveRepository<DocumentOperationEntity, String> operations;
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final ReactiveRepository<PaymentAllocationEntity, String> paymentLines;
    private final ReactiveRepository<FundEntryEntity, String> fundEntries;
    private final ReactiveRepository<FundConsumptionEntity, String> fundConsumptions;

    public Mono<PagerResult<DocumentEntity>> query(
            String project, String type, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(
                        Mono.defer(
                                () -> {
                                    DocumentStore.validateType(type);
                                    return TenantHolder.currentId()
                                            .defaultIfEmpty("")
                                            .flatMap(
                                                    tenant ->
                                                            documentPage(
                                                                    project, type, tenant, input));
                                }));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Mono<PagerResult<DocumentLedgerRow>> ledger(
            String project, String type, QueryParamEntity input) {
        return QueryHelper.transformPageResult(query(project, type, input), this::rows);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Mono<DocumentStore.Detail> detail(String project, String type, String id) {
        return scope.project(project, CrudAssetPermission.read)
                .then(TenantHolder.currentId().defaultIfEmpty(""))
                .flatMap(tenant -> store.require(project, type, id, tenant, false))
                .flatMap(row -> store.detail(row, CrudAssetPermission.read))
                .flatMap(
                        detail ->
                                row(detail)
                                        .map(
                                                row ->
                                                        new DocumentStore.Detail(
                                                                row,
                                                                detail.buyer(),
                                                                detail.rule(),
                                                                detail.lines())));
    }

    /** 导出整张票据；先检查真实总数，超限明确拒绝，不能截断后下载。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Mono<List<DocumentLedgerRow>> exportData(
            String project, String type, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(TenantHolder.currentId().defaultIfEmpty(""))
                .flatMap(
                        tenant ->
                                visibility
                                        .documents(project)
                                        .flatMap(
                                                permitted -> {
                                                    DocumentStore.validateType(type);
                                                    var q = page(input);
                                                    q.setPaging(false);
                                                    q.toNestQuery(
                                                            n ->
                                                                    n.and("projectId", project)
                                                                            .and("tenantId", tenant)
                                                                            .and(
                                                                                    "documentType",
                                                                                    type)
                                                                            .and("deleted", false)
                                                                            .accept(permitted));
                                                    return documents
                                                            .createQuery()
                                                            .setParam(q)
                                                            .count()
                                                            .flatMap(
                                                                    count -> {
                                                                        if (count > 10000)
                                                                            return Mono.error(
                                                                                    FinanceScope
                                                                                            .error(
                                                                                                    "report_export_limit",
                                                                                                    400));
                                                                        return documents
                                                                                .createQuery()
                                                                                .setParam(q)
                                                                                .fetch()
                                                                                .take(10001)
                                                                                .collectList()
                                                                                .flatMap(
                                                                                        found ->
                                                                                                found
                                                                                                                        .size()
                                                                                                                > 10000
                                                                                                        ? Mono
                                                                                                                .error(
                                                                                                                        FinanceScope
                                                                                                                                .error(
                                                                                                                                        "report_export_limit",
                                                                                                                                        400))
                                                                                                        : rows(
                                                                                                                found));
                                                                    });
                                                }));
    }

    private Mono<List<DocumentLedgerRow>> rows(List<DocumentEntity> rows) {
        return Flux.fromIterable(rows)
                .concatMap(row -> store.detail(row, CrudAssetPermission.read).flatMap(this::row))
                .collectList();
    }

    private Mono<DocumentLedgerRow> row(DocumentStore.Detail detail) {
        var row = new DocumentLedgerRow();
        org.springframework.beans.BeanUtils.copyProperties(detail.document(), row);
        var snapshots = detail.lines().stream().map(DocumentStore.Line::snapshot).toList();
        row.setSources(snapshots);
        row.setTimeLimitType(detail.rule().timeLimitType());
        row.setLimitDays(detail.rule().limitDays());
        row.setMaxReopenTimes(detail.rule().maxReopenTimes());
        row.setBuyerTitleType(detail.buyer().titleType());
        row.setFullName(
                join(snapshots.stream().map(DocumentPreparationService.Line::fullName).toList()));
        row.setSubjectName(
                join(
                        snapshots.stream()
                                .map(DocumentPreparationService.Line::subjectName)
                                .toList()));
        row.setSourceType(
                join(snapshots.stream().map(DocumentPreparationService.Line::sourceType).toList()));
        row.setPeriod(
                join(
                        snapshots.stream()
                                .map(
                                        l ->
                                                Objects.toString(l.startDate(), "—")
                                                        + " ~ "
                                                        + Objects.toString(l.endDate(), "—"))
                                .toList()));
        boolean knownBasis = snapshots.stream().allMatch(l -> l.allowedAmount() != null);
        boolean knownIssuePayment = snapshots.stream().allMatch(l -> l.paidAmount() != null);
        var allowed =
                snapshots.stream()
                        .map(
                                l ->
                                        l.allowedAmount() == null
                                                ? BigDecimal.ZERO
                                                : new BigDecimal(l.allowedAmount()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        var paid =
                snapshots.stream()
                        .map(
                                l ->
                                        l.paidAmount() == null
                                                ? BigDecimal.ZERO
                                                : new BigDecimal(l.paidAmount()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        row.setIssuePaidAmount(knownIssuePayment ? paid.toPlainString() : null);
        row.setIssuePaymentStatus(
                knownBasis && knownIssuePayment ? paymentStatus(paid, allowed) : "UNKNOWN");
        // 实时金额只读本财务来源事实，不重新读取税配置；损坏或缺失来源保持UNKNOWN。
        return Flux.fromIterable(snapshots)
                .concatMap(
                        l ->
                                currentPaid(row.getProjectId(), l)
                                        .map(Optional::of)
                                        .defaultIfEmpty(Optional.empty()))
                .collectList()
                .flatMap(
                        values -> {
                            boolean known = values.stream().allMatch(Optional::isPresent);
                            var current =
                                    values.stream()
                                            .flatMap(Optional::stream)
                                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                            row.setPaidAmount(known ? current.toPlainString() : null);
                            row.setPaymentStatus(
                                    known && knownBasis
                                            ? paymentStatus(current, allowed)
                                            : "UNKNOWN");
                            return operations
                                    .createQuery()
                                    .where("projectId", row.getProjectId())
                                    .and(
                                            row.getOriginalDocumentId() == null
                                                    ? "documentId"
                                                    : "resultDocumentId",
                                            row.getId())
                                    .and(
                                            "action",
                                            row.getOriginalDocumentId() == null
                                                    ? "ISSUE"
                                                    : "REVERSE")
                                    .and("deleted", false)
                                    .fetchOne()
                                    .map(op -> Objects.toString(op.getModifierName(), ""))
                                    .defaultIfEmpty("")
                                    .map(
                                            name -> {
                                                row.setIssuerName(name);
                                                return row;
                                            });
                        });
    }

    private Mono<BigDecimal> currentPaid(String project, DocumentPreparationService.Line line) {
        if ("RECEIVABLE".equals(line.sourceType()))
            return bills.createQuery()
                    .where("projectId", project)
                    .and("id", line.sourceId())
                    .and("deleted", false)
                    .fetchOne()
                    .flatMap(bill -> Mono.justOrEmpty(bill.getPaidAmount()));
        if ("TEMP_PAYMENT".equals(line.sourceType()))
            return paymentLines
                    .createQuery()
                    .where("projectId", project)
                    .and("paymentId", line.sourceId())
                    .and("deleted", false)
                    .fetch()
                    .collectList()
                    .filter(lines -> !lines.isEmpty())
                    .map(
                            lines ->
                                    lines.stream()
                                            .map(l -> l.getAmount().subtract(l.getRefundedAmount()))
                                            .reduce(BigDecimal.ZERO, BigDecimal::add));
        if ("FUND_TOPUP".equals(line.sourceType()))
            return fundEntries
                    .createQuery()
                    .where("projectId", project)
                    .and("id", line.sourceId())
                    .and("deleted", false)
                    .fetchOne()
                    .flatMap(
                            entry ->
                                    fundConsumptions
                                            .createQuery()
                                            .where("projectId", project)
                                            .and("creditId", line.sourceId())
                                            .and("deleted", false)
                                            .fetch()
                                            .concatMap(
                                                    consumed ->
                                                            fundEntries
                                                                    .createQuery()
                                                                    .where("projectId", project)
                                                                    .and(
                                                                            "id",
                                                                            consumed
                                                                                    .getDebitEntryId())
                                                                    .and("deleted", false)
                                                                    .lt("cashAmount", 0)
                                                                    .fetchOne()
                                                                    .map(
                                                                            debit ->
                                                                                    consumed
                                                                                            .getAmount()))
                                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                                            .map(
                                                    refunded ->
                                                            entry.getAmount()
                                                                    .subtract(refunded)
                                                                    .max(BigDecimal.ZERO)));
        return Mono.empty();
    }

    private static String paymentStatus(BigDecimal paid, BigDecimal allowed) {
        return paid.signum() <= 0 ? "UNPAID" : paid.compareTo(allowed) >= 0 ? "PAID" : "PARTIAL";
    }

    private static String join(List<String> values) {
        return values.stream()
                .filter(Objects::nonNull)
                .filter(v -> !v.isBlank())
                .distinct()
                .collect(Collectors.joining(" / "));
    }

    private Mono<PagerResult<DocumentEntity>> documentPage(
            String project, String type, String tenant, QueryParamEntity input) {
        return visibility
                .documents(project)
                .flatMap(
                        permitted -> {
                            var q = page(input);
                            // 用户OR、分页和加锁参数不能替换系统范围，权限在同一个SQL中先于分页应用。
                            q.toNestQuery(
                                    n ->
                                            n.and("projectId", project)
                                                    .and("tenantId", tenant)
                                                    .and("documentType", type)
                                                    .and("deleted", false)
                                                    .accept(permitted));
                            return QueryHelper.queryPager(q, documents::createQuery);
                        });
    }

    public Mono<PagerResult<DocumentOperationEntity>> logs(
            String project, String type, String id, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(
                        Mono.defer(
                                () -> {
                                    DocumentStore.validateType(type);
                                    return TenantHolder.currentId()
                                            .defaultIfEmpty("")
                                            .flatMap(
                                                    tenant ->
                                                            authorizedLogs(
                                                                    project, type, id, tenant,
                                                                    input));
                                }));
    }

    private Mono<PagerResult<DocumentOperationEntity>> authorizedLogs(
            String project, String type, String id, String tenant, QueryParamEntity input) {
        return store.require(project, type, id, tenant, false)
                .flatMap(row -> store.lines(row, CrudAssetPermission.read))
                .then(
                        Mono.defer(
                                () -> {
                                    var q = page(input);
                                    // 红冲日志同时属于原蓝票及生成的红票，两个入口仍先验证自身完整空间权限。
                                    q.toNestQuery(
                                            n ->
                                                    n.and("projectId", project)
                                                            .and("tenantId", tenant)
                                                            .and("documentType", type)
                                                            .and("deleted", false)
                                                            .nest()
                                                            .and("documentId", id)
                                                            .or("resultDocumentId", id)
                                                            .end());
                                    return QueryHelper.queryPager(q, operations::createQuery);
                                }));
    }

    private static QueryParamEntity page(QueryParamEntity input) {
        var q = SubjectService.page(input == null ? new QueryParamEntity() : input);
        q.setForUpdate(false);
        q.setTerms(snapshotTerms(q.getTerms()));
        if (q.getSorts().isEmpty()) q.toQuery().orderByDesc("createTime").orderByDesc("id");
        return q;
    }

    private static List<Term> snapshotTerms(List<Term> terms) {
        var result = new ArrayList<Term>();
        if (terms == null) return result;
        for (var term : terms) {
            if ("paymentStatus".equals(term.getColumn())) {
                var filter = paymentFilter(term);
                filter.setType(term.getType());
                result.add(filter);
            } else if (Set.of("fullName", "subjectName", "sourceType")
                    .contains(Objects.toString(term.getColumn(), ""))) {
                var operator =
                        switch (Objects.toString(term.getTermType(), "eq")) {
                            case "eq" -> "=";
                            case "like" -> "like";
                            default -> throw FinanceScope.error("document_input_invalid", 400);
                        };
                var filter =
                        SqlTerm.of(
                                "id in (select document_id from pm_fin_doc_line where deleted"
                                        + " = false and cast(line_snapshot_json as jsonb) ->> ? "
                                        + operator
                                        + " ?)",
                                term.getColumn(),
                                term.getValue());
                filter.setType(term.getType());
                result.add(filter);
            } else {
                term.setTerms(snapshotTerms(term.getTerms()));
                result.add(term);
            }
        }
        return result;
    }

    /** 实时收款筛选在分页前按整票聚合，不能用开具场景代替收款状态。 */
    private static SqlTerm paymentFilter(Term term) {
        if (!"eq".equals(term.getTermType())
                || !Set.of("PAID", "PARTIAL", "UNPAID", "UNKNOWN")
                        .contains(Objects.toString(term.getValue(), "")))
            throw FinanceScope.error("document_input_invalid", 400);
        return SqlTerm.of(
                """
id in (select document_id from (
  select l.document_id, cast(cast(l.line_snapshot_json as jsonb)->>'allowedAmount' as numeric) basis,
    case l.source_type
      when 'RECEIVABLE' then (select r.paid_amount from pm_fin_receivable r where r.id=l.source_id and r.project_id=l.project_id and r.deleted=false)
      when 'TEMP_PAYMENT' then (select sum(p.amount-p.refunded_amount) from pm_fin_payment_line p where p.payment_id=l.source_id and p.project_id=l.project_id and p.deleted=false)
      when 'FUND_TOPUP' then (select greatest(f.amount-coalesce((select sum(c.amount) from pm_fin_fund_consumption c join pm_fin_fund_entry d on d.id=c.debit_entry_id and d.project_id=c.project_id and d.deleted=false and d.cash_amount<0 where c.credit_id=f.id and c.project_id=f.project_id and c.deleted=false),0),0) from pm_fin_fund_entry f where f.id=l.source_id and f.project_id=l.project_id and f.deleted=false)
      else null end paid
  from pm_fin_doc_line l where l.deleted=false
) source group by document_id
  having (case when count(paid)<>count(*) or count(basis)<>count(*) then 'UNKNOWN'
    when sum(paid)<=0 then 'UNPAID' when sum(paid)>=sum(basis) then 'PAID' else 'PARTIAL' end)=?)
""",
                term.getValue());
    }
}
