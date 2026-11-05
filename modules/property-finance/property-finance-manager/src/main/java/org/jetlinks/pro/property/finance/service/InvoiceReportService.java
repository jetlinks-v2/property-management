package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.executor.wrapper.ResultWrappers;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.hswebframework.web.api.crud.entity.*;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.PaymentRules;
import org.jetlinks.pro.property.finance.report.*;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import reactor.core.publisher.*;

import java.util.*;

/** 规范来源覆盖统计；发票金额来自有效发票占用，不使用混含收据的共享计数。 */
@Service
@RequiredArgsConstructor
public class InvoiceReportService {
    public static final int EXPORT_LIMIT = 10000;
    private static final String SUMS =
            "count(*) as rows, sum(basis) as basis, sum(paid) as paid, "
                    + "sum(invoiced) as invoiced, sum(unissued) as unissued";
    private final FinanceScope scope;
    private final DocumentQueryScope visibility;
    private final DatabaseOperator database;
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final ReactiveRepository<PaymentEntity, String> payments;
    private final ReactiveRepository<PaymentAllocationEntity, String> paymentLines;
    private final ReactiveRepository<FundEntryEntity, String> entries;
    private final ReactiveRepository<FundAccountEntity, String> accounts;
    private final ReactiveRepository<DocumentEntity, String> documents;
    private final ReactiveRepository<DocumentOccupationEntity, String> occupations;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Mono<InvoiceReportResult> query(InvoiceReportInput input) {
        return prepare(input)
                .flatMap(p -> load(input, p, false))
                .as(MonoTracer.create("/property/finance/reports/invoices/query"));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Mono<InvoiceReportResult> exportData(InvoiceReportInput input) {
        return prepare(input)
                .flatMap(p -> load(input, p, true))
                .as(MonoTracer.create("/property/finance/reports/invoices/export"));
    }

    private Mono<Prepared> prepare(InvoiceReportInput i) {
        return Mono.defer(
                () -> {
                    if (i == null) return Mono.error(error("report_query_invalid", 400));
                    i.validate();
                    return Flux.fromIterable(new LinkedHashSet<>(i.getProjectIds()))
                            .concatMap(id -> scope.project(id, CrudAssetPermission.read))
                            .collectMap(p -> p.id(), p -> p.name(), LinkedHashMap::new)
                            .flatMap(
                                    names ->
                                            TenantHolder.currentId()
                                                    .defaultIfEmpty("")
                                                    .flatMap(tenant -> source(i, names, tenant)));
                });
    }

    private Mono<Prepared> source(InvoiceReportInput i, Map<String, String> names, String tenant) {
        var b = base(i);
        b.toQuery().nest().isNull("split").or().is("split", false).end();
        var l = base(i);
        l.toQuery().and("sourceType", "TEMPORARY");
        var e = base(i);
        e.toQuery()
                .and("action", "TOPUP")
                .gt("cashAmount", 0)
                .in("method", PaymentRules.MANUAL_METHODS);
        // 每个来源先做空间授权。临时主单及票据的完整权限另外在主单SQL中约束。
        return visibility
                .inject(b, CrudAssetPermission.read)
                .then(visibility.inject(l, CrudAssetPermission.read))
                .then(visibility.inject(e, CrudAssetPermission.read))
                .thenMany(
                        Flux.fromIterable(names.keySet())
                                .concatMap(
                                        project ->
                                                visibility
                                                        .allRelated(
                                                                paymentLines,
                                                                "paymentId",
                                                                project,
                                                                CrudAssetPermission.read)
                                                        .flatMap(
                                                                allowedPayments ->
                                                                        visibility
                                                                                .documents(project)
                                                                                .map(
                                                                                        allowedDocs -> {
                                                                                            var p =
                                                                                                    base(
                                                                                                            i);
                                                                                            p.toQuery()
                                                                                                    .and(
                                                                                                            "projectId",
                                                                                                            project)
                                                                                                    .and(
                                                                                                            "sourceType",
                                                                                                            "TEMPORARY")
                                                                                                    .and(
                                                                                                            "status",
                                                                                                            "SUCCEEDED")
                                                                                                    .accept(
                                                                                                            allowedPayments);
                                                                                            var d =
                                                                                                    base(
                                                                                                            i);
                                                                                            d.toQuery()
                                                                                                    .and(
                                                                                                            "projectId",
                                                                                                            project)
                                                                                                    .and(
                                                                                                            "tenantId",
                                                                                                            tenant)
                                                                                                    .and(
                                                                                                            "documentType",
                                                                                                            "INVOICE")
                                                                                                    .accept(
                                                                                                            allowedDocs);
                                                                                            return new ProjectSql(
                                                                                                    p,
                                                                                                    d);
                                                                                        }))))
                .collectList()
                .map(projects -> build(i, names, tenant, b, l, e, projects));
    }

    private Prepared build(
            InvoiceReportInput i,
            Map<String, String> names,
            String tenant,
            QueryParamEntity b,
            QueryParamEntity l,
            QueryParamEntity e,
            List<ProjectSql> projects) {
        var params = new ArrayList<Object>();
        String bs =
                sql(
                        bills,
                        b,
                        params,
                        "id",
                        "projectId",
                        "spaceId",
                        "fullName",
                        "customerName",
                        "subjectId",
                        "subjectName",
                        "actualReceivableAmount",
                        "paidAmount");
        var ps = new ArrayList<String>();
        for (var p : projects) ps.add(sql(payments, p.payments(), params, "id", "projectId"));
        String ls =
                sql(
                        paymentLines,
                        l,
                        params,
                        "id",
                        "projectId",
                        "paymentId",
                        "spaceId",
                        "fullName",
                        "customerName",
                        "subjectId",
                        "subjectName",
                        "amount",
                        "refundedAmount");
        String es =
                sql(
                        entries,
                        e,
                        params,
                        "id",
                        "projectId",
                        "accountId",
                        "spaceId",
                        "fullName",
                        "customerName",
                        "subjectName",
                        "amount");
        // 账户仅作稳定科目归属，逻辑删除不使原存入历史消失。
        String as = sql(accounts, history(i), params, "id", "projectId", "subjectId");
        var dq = base(i);
        dq.toQuery()
                .and("tenantId", tenant)
                .and("documentType", "INVOICE")
                .in("status", List.of("SIMULATED_ISSUED", "SIMULATED_REVERSED"));
        String ds =
                sql(
                        documents,
                        dq,
                        params,
                        "id",
                        "projectId",
                        "documentNumber",
                        "invoiceType",
                        "issuedAt",
                        "createTime",
                        "buyerName",
                        "status");
        var oq = base(i);
        oq.toQuery().and("documentType", "INVOICE");
        String os =
                sql(
                        occupations,
                        oq,
                        params,
                        "documentId",
                        "projectId",
                        "sourceId",
                        "sourceType",
                        "state",
                        "amount");
        var vs = new ArrayList<String>();
        for (var p : projects) vs.add(sql(documents, p.documents(), params, "id"));
        String with =
                "with b as ("
                        + bs
                        + "), p as ("
                        + String.join(" union all ", ps)
                        + "), l as ("
                        + ls
                        + "), e as ("
                        + es
                        + "), a as ("
                        + as
                        + "), d as ("
                        + ds
                        + "), o as ("
                        + os
                        + "), v as ("
                        + String.join(" union all ", vs)
                        + "), sources as (select b.\"id\", 'RECEIVABLE' as \"sourceType\","
                        + " b.\"projectId\", b.\"spaceId\", b.\"fullName\", b.\"customerName\","
                        + " b.\"subjectId\", b.\"subjectName\", b.\"actualReceivableAmount\" as"
                        + " basis, b.\"paidAmount\" as paid from b union all select p.\"id\","
                        + " 'TEMP_PAYMENT', p.\"projectId\", min(l.\"spaceId\"),"
                        + " min(l.\"fullName\"), min(l.\"customerName\"), min(l.\"subjectId\"),"
                        + " min(l.\"subjectName\"), sum(l.\"amount\"-l.\"refundedAmount\"),"
                        + " sum(l.\"amount\"-l.\"refundedAmount\") from p join l on"
                        + " p.\"id\"=l.\"paymentId\" and p.\"projectId\"=l.\"projectId\" group by"
                        + " p.\"id\", p.\"projectId\" union all select e.\"id\", 'FUND_TOPUP',"
                        + " e.\"projectId\", e.\"spaceId\", e.\"fullName\", e.\"customerName\","
                        + " a.\"subjectId\", e.\"subjectName\", e.\"amount\", e.\"amount\" from e"
                        + " join a on e.\"accountId\"=a.\"id\" and"
                        + " e.\"projectId\"=a.\"projectId\"), issued as (select o.\"projectId\","
                        + " o.\"sourceType\", o.\"sourceId\", sum(o.\"amount\") as invoiced from o"
                        + " join d on o.\"documentId\"=d.\"id\" and o.\"projectId\"=d.\"projectId\""
                        + " where o.\"state\"='ACTIVE' and d.\"status\"='SIMULATED_ISSUED' group by"
                        + " o.\"projectId\", o.\"sourceType\", o.\"sourceId\"), latest as (select"
                        + " o.\"projectId\", o.\"sourceType\", o.\"sourceId\", d.\"id\","
                        + " d.\"documentNumber\", d.\"invoiceType\", d.\"issuedAt\","
                        + " d.\"buyerName\", row_number() over (partition by o.\"projectId\","
                        + " o.\"sourceType\", o.\"sourceId\" order by d.\"issuedAt\" desc,"
                        + " d.\"createTime\" desc, d.\"id\" desc) as rank from o join d on"
                        + " o.\"documentId\"=d.\"id\" and o.\"projectId\"=d.\"projectId\"), covered"
                        + " as (select s.*, coalesce(issued.invoiced,0) as invoiced, case when"
                        + " s.basis>coalesce(issued.invoiced,0) then"
                        + " s.basis-coalesce(issued.invoiced,0) else 0 end as unissued, case when"
                        + " coalesce(issued.invoiced,0)>0 then case when issued.invoiced>=s.basis"
                        + " then 'INVOICED' else 'PARTIAL' end when h.\"id\" is not null then"
                        + " 'REVERSED' else 'UNISSUED' end as \"invoiceStatus\", case when v.\"id\""
                        + " is not null then h.\"id\" end as \"documentId\", case when v.\"id\" is"
                        + " not null then h.\"documentNumber\" end as \"invoiceNo\", case when"
                        + " v.\"id\" is not null then h.\"invoiceType\" end as \"invoiceType\","
                        + " case when v.\"id\" is not null then h.\"issuedAt\" end as"
                        + " \"invoiceDate\", case when v.\"id\" is not null then h.\"buyerName\""
                        + " end as \"buyerName\" from sources s left join issued on"
                        + " s.\"id\"=issued.\"sourceId\" and s.\"projectId\"=issued.\"projectId\""
                        + " and s.\"sourceType\"=issued.\"sourceType\" left join latest h on"
                        + " s.\"id\"=h.\"sourceId\" and s.\"projectId\"=h.\"projectId\" and"
                        + " s.\"sourceType\"=h.\"sourceType\" and h.rank=1 left join v on"
                        + " h.\"id\"=v.\"id\") ";
        var where = new ArrayList<String>();
        in(where, params, "spaceId", i.getSpaceIds());
        in(where, params, "subjectId", i.getSubjectIds());
        eq(where, params, "sourceType", i.getSourceType());
        eq(where, params, "invoiceStatus", i.getInvoiceStatus());
        eq(where, params, "invoiceType", i.getInvoiceType());
        like(where, params, "customerName", i.getCustomerName());
        like(where, params, "fullName", i.getFullName());
        like(where, params, "id", i.getSourceId());
        like(where, params, "invoiceNo", i.getInvoiceNo());
        if (i.getInvoiceDateStart() != null) {
            where.add("substring(\"invoiceDate\" from 1 for 10)>=?");
            params.add(i.getInvoiceDateStart());
        }
        if (i.getInvoiceDateEnd() != null) {
            where.add("substring(\"invoiceDate\" from 1 for 10)<=?");
            params.add(i.getInvoiceDateEnd());
        }
        String source =
                where.isEmpty()
                        ? "covered"
                        : "(select * from covered where "
                                + String.join(" and ", where)
                                + ") filtered";
        return new Prepared(with, source, params.toArray(), names);
    }

    private Mono<InvoiceReportResult> load(InvoiceReportInput i, Prepared p, boolean export) {
        String group =
                switch (i.getGroupBy()) {
                    case "PROJECT" -> "\"projectId\"";
                    case "SUBJECT" -> "\"subjectId\", \"subjectName\"";
                    default -> "";
                };
        String count =
                group.isEmpty()
                        ? "select count(*) as total from " + p.source()
                        : "select count(*) as total from (select "
                                + group
                                + " from "
                                + p.source()
                                + " group by "
                                + group
                                + ") groups";
        return select(p, "select " + SUMS + " from " + p.source(), List.of())
                .single()
                .flatMap(
                        summary ->
                                select(p, count, List.of())
                                        .single()
                                        .flatMap(
                                                totalRow -> {
                                                    long total =
                                                            ((Number) totalRow.get("total"))
                                                                    .longValue();
                                                    if (total > Integer.MAX_VALUE
                                                            || export && total > EXPORT_LIMIT)
                                                        return Mono.error(
                                                                error(
                                                                        export
                                                                                ? "report_export_limit"
                                                                                : "report_result_limit",
                                                                        400));
                                                    int index = export ? 0 : i.getPageIndex(),
                                                            size =
                                                                    export
                                                                            ? EXPORT_LIMIT
                                                                            : i.getPageSize();
                                                    String sql =
                                                            group.isEmpty()
                                                                    ? "select *, 1 as rows from "
                                                                            + p.source()
                                                                            + " order by"
                                                                            + " \"projectId\","
                                                                            + " \"sourceType\","
                                                                            + " \"id\""
                                                                    : "select "
                                                                            + group
                                                                            + ", "
                                                                            + SUMS
                                                                            + " from "
                                                                            + p.source()
                                                                            + " group by "
                                                                            + group
                                                                            + " order by "
                                                                            + group;
                                                    return select(
                                                                    p,
                                                                    sql
                                                                            + " offset ? rows fetch"
                                                                            + " next ? rows only",
                                                                    List.of(
                                                                            (long) index * size,
                                                                            size))
                                                            .map(r -> row(r, p.names()))
                                                            .collectList()
                                                            .map(
                                                                    rows -> {
                                                                        var page =
                                                                                new QueryParamEntity();
                                                                        page.setPageIndex(index);
                                                                        page.setPageSize(size);
                                                                        return new InvoiceReportResult(
                                                                                i.getGroupBy(),
                                                                                InvoiceReportAmounts
                                                                                        .of(
                                                                                                summary),
                                                                                PagerResult.of(
                                                                                        (int) total,
                                                                                        rows,
                                                                                        page));
                                                                    });
                                                }));
    }

    private Flux<Map<String, Object>> select(Prepared p, String sql, List<Object> extra) {
        var params = new ArrayList<>(Arrays.asList(p.params()));
        params.addAll(extra);
        return database.sql()
                .reactive()
                .select(SqlRequests.of(p.with() + sql, params.toArray()), ResultWrappers.map());
    }

    private static QueryParamEntity history(InvoiceReportInput i) {
        var q = new QueryParamEntity();
        q.setPaging(false);
        q.toQuery().in("projectId", i.getProjectIds());
        return q;
    }

    private static QueryParamEntity base(InvoiceReportInput i) {
        var q = history(i);
        q.toQuery().and("deleted", false);
        return q;
    }

    private static String sql(
            ReactiveRepository<?, ?> repo,
            QueryParamEntity q,
            List<Object> params,
            String... cols) {
        var request = repo.nativeQuery().select(cols).setParam(q).getSql();
        params.addAll(Arrays.asList(request.getParameters()));
        return request.getSql();
    }

    private static void eq(List<String> w, List<Object> p, String column, String value) {
        if (value != null) {
            w.add("\"" + column + "\"=?");
            p.add(value);
        }
    }

    private static void like(List<String> w, List<Object> p, String column, String value) {
        if (value != null && !value.isBlank()) {
            w.add("lower(\"" + column + "\") like ?");
            p.add("%" + value.trim().toLowerCase(Locale.ROOT) + "%");
        }
    }

    private static void in(List<String> w, List<Object> p, String column, List<String> values) {
        if (!values.isEmpty()) {
            w.add(
                    "\""
                            + column
                            + "\" in ("
                            + String.join(",", Collections.nCopies(values.size(), "?"))
                            + ")");
            p.addAll(values);
        }
    }

    private static String text(Map<String, Object> r, String key) {
        return Objects.toString(r.get(key), null);
    }

    private static InvoiceReportRow row(Map<String, Object> r, Map<String, String> names) {
        return new InvoiceReportRow(
                text(r, "id"),
                text(r, "sourceType"),
                text(r, "projectId"),
                names.get(text(r, "projectId")),
                text(r, "spaceId"),
                text(r, "fullName"),
                text(r, "customerName"),
                text(r, "subjectId"),
                text(r, "subjectName"),
                text(r, "invoiceStatus"),
                text(r, "documentId"),
                text(r, "invoiceNo"),
                text(r, "invoiceType"),
                text(r, "invoiceDate"),
                text(r, "buyerName"),
                ((Number) r.get("rows")).longValue(),
                InvoiceReportAmounts.of(r));
    }

    private record ProjectSql(QueryParamEntity payments, QueryParamEntity documents) {}

    private record Prepared(
            String with, String source, Object[] params, Map<String, String> names) {}
}
