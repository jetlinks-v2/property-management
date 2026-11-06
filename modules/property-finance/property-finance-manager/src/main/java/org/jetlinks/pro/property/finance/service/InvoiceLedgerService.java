package org.jetlinks.pro.property.finance.service;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.core.param.SqlTerm;
import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.executor.wrapper.ResultWrappers;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.hswebframework.web.api.crud.entity.*;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.report.*;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import reactor.core.publisher.*;

import java.math.BigDecimal;
import java.util.*;

/** 发票流水按整票授权、整票汇总，先聚合明细再关联，避免一票多房屋放大票面金额。 */
@Service
@RequiredArgsConstructor
public class InvoiceLedgerService {
    private final FinanceScope scope;
    private final DocumentQueryScope visibility;
    private final ReactiveRepository<DocumentEntity, String> documents;
    private final ReactiveRepository<DocumentLineEntity, String> lines;
    private final DatabaseOperator database;
    private static final String SUMS =
            "count(*) as total, sum(\"amount\") as gross, sum(\"netAmount\") as net,"
                    + " sum(\"taxAmount\") as tax, sum(difference) as difference, sum(output) as"
                    + " output";

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Mono<InvoiceLedgerResult> query(InvoiceLedgerInput i) {
        return load(i, false)
                .as(MonoTracer.create("/property/finance/reports/invoices/ledger/query"));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Mono<ReportWorkbook> exportWorkbook(InvoiceLedgerInput i, Locale locale) {
        return load(i, true)
                .flatMap(
                        result ->
                                ReportWorkbookLoader.load(
                                        i,
                                        i.getProjectIds(),
                                        scope,
                                        () -> Mono.just(InvoiceLedgerFile.data(result, locale)),
                                        () -> Mono.just(InvoiceLedgerFile.data(result, locale)),
                                        locale));
    }

    private Mono<InvoiceLedgerResult> load(InvoiceLedgerInput input, boolean export) {
        return Mono.defer(
                () -> {
                    input.validate();
                    return Flux.fromIterable(new LinkedHashSet<>(input.getProjectIds()))
                            .concatMap(id -> scope.project(id, CrudAssetPermission.read))
                            .collectMap(p -> p.id(), p -> p.name(), LinkedHashMap::new)
                            .flatMap(
                                    names ->
                                            prepare(input, names.keySet())
                                                    .flatMap(
                                                            sql ->
                                                                    page(
                                                                            input, export, sql,
                                                                            names)));
                });
    }

    private Mono<LedgerSql> prepare(InvoiceLedgerInput input, Set<String> projects) {
        return TenantHolder.currentId()
                .defaultIfEmpty("")
                .flatMap(
                        tenant ->
                                Flux.fromIterable(projects)
                                        .concatMap(
                                                project ->
                                                        visibility
                                                                .documents(project)
                                                                .map(
                                                                        permitted ->
                                                                                documentSql(
                                                                                        input,
                                                                                        project,
                                                                                        tenant,
                                                                                        permitted)))
                                        .collectList()
                                        .map(
                                                parts -> {
                                                    var params = new ArrayList<Object>();
                                                    var sources = new ArrayList<String>();
                                                    for (var part : parts) {
                                                        sources.add(part.sql());
                                                        params.addAll(Arrays.asList(part.params()));
                                                    }
                                                    // 先限制整票权限，再聚合授权票据明细；一票多行不能放大票面金额。
                                                    String lineSql =
                                                            "select document_id,"
                                                                + " string_agg(distinct"
                                                                + " (cast(line_snapshot_json as"
                                                                + " jsonb)->>'fullName'), ' / ') as"
                                                                + " \"fullName\","
                                                                + " string_agg(distinct"
                                                                + " (cast(line_snapshot_json as"
                                                                + " jsonb)->>'subjectName'), ' / ')"
                                                                + " as \"subjectName\","
                                                                + " string_agg(distinct"
                                                                + " (cast(line_snapshot_json as"
                                                                + " jsonb)->>'collectionMethod'), '"
                                                                + " / ') as \"collectionMethod\","
                                                                + " sum(sign(amount)*abs(coalesce(cast(cast(line_snapshot_json"
                                                                + " as jsonb)->>'differenceAmount'"
                                                                + " as numeric),0))) as difference,"
                                                                + " sum(sign(amount)*abs(coalesce(cast(cast(line_snapshot_json"
                                                                + " as jsonb)->>'outputTax' as"
                                                                + " numeric),tax_amount))) as"
                                                                + " output from pm_fin_doc_line"
                                                                + " where deleted=false and"
                                                                + " document_id in (select \"id\""
                                                                + " from ledger_docs) group by"
                                                                + " document_id";
                                                    String source =
                                                            "(select d.*, origin.document_number as"
                                                                + " \"originalInvoiceNo\","
                                                                + " l.\"fullName\",l.\"subjectName\",l.\"collectionMethod\",l.difference,l.output"
                                                                + " from ledger_docs d join ("
                                                                    + lineSql
                                                                    + ") l on"
                                                                    + " d.\"id\"=l.document_id left"
                                                                    + " join pm_fin_document origin"
                                                                    + " on coalesce(d.\"originalDocumentId\",d.\"previousDocumentId\")=origin.id"
                                                                    + " and d.\"projectId\"=origin.project_id)"
                                                                    + " invoice_ledger";
                                                    return new LedgerSql(
                                                            "with ledger_docs as ("
                                                                    + String.join(
                                                                            " union all ", sources)
                                                                    + ") ",
                                                            source,
                                                            params);
                                                }));
    }

    private Mono<InvoiceLedgerResult> page(
            InvoiceLedgerInput input, boolean export, LedgerSql sql, Map<String, String> names) {
        return select(sql.cte() + "select " + SUMS + " from " + sql.source(), sql.params())
                .single()
                .flatMap(
                        total -> {
                            long count = ((Number) total.get("total")).longValue();
                            if (count > Integer.MAX_VALUE || export && count > 10000)
                                return Mono.error(
                                        FinanceScope.error(
                                                export
                                                        ? "report_export_limit"
                                                        : "report_result_limit",
                                                400));
                            int index = export ? 0 : input.getPageIndex(),
                                    size = export ? 10000 : input.getPageSize();
                            var params = new ArrayList<>(sql.params());
                            params.add((long) index * size);
                            params.add(size);
                            return select(
                                            sql.cte()
                                                    + "select * from "
                                                    + sql.source()
                                                    + " order by \"issuedAt\" desc, \"id\" offset ?"
                                                    + " rows fetch next ? rows only",
                                            params)
                                    .map(row -> view(row, names))
                                    .collectList()
                                    .map(
                                            rows -> {
                                                var page = new QueryParamEntity();
                                                page.setPageIndex(index);
                                                page.setPageSize(size);
                                                return new InvoiceLedgerResult(
                                                        "TICKET",
                                                        amounts(total),
                                                        PagerResult.of((int) count, rows, page));
                                            });
                        });
    }

    private record LedgerSql(String cte, String source, List<Object> params) {}

    private Sql documentSql(
            InvoiceLedgerInput i, String project, String tenant, SqlTerm permitted) {
        var q = new QueryParamEntity();
        q.setPaging(false);
        var t =
                q.toQuery()
                        .and("projectId", project)
                        .and("tenantId", tenant)
                        .and("documentType", "INVOICE")
                        .and("deleted", false);
        q.getTerms().add(permitted);
        if (i.getInvoiceNo() != null) t.like("documentNumber", "%" + i.getInvoiceNo() + "%");
        if (i.getCustomerName() != null) t.like("customerName", "%" + i.getCustomerName() + "%");
        if (i.getBuyerName() != null) t.like("buyerName", "%" + i.getBuyerName() + "%");
        if (i.getInvoiceType() != null) t.and("invoiceType", i.getInvoiceType());
        if (i.getTicketStatus() != null) t.and("status", i.getTicketStatus());
        if (i.getTicketColor() != null) {
            if ("RED".equals(i.getTicketColor())) t.lt("amount", 0);
            else t.gte("amount", 0);
        }
        if (i.getInvoiceDateStart() != null)
            t.gte("issuedAt", i.getInvoiceDateStart() + "T00:00:00");
        if (i.getInvoiceDateEnd() != null)
            t.lt(
                    "issuedAt",
                    java.time.LocalDate.parse(i.getInvoiceDateEnd()).plusDays(1) + "T00:00:00");
        var lq = new QueryParamEntity();
        lq.setPaging(false);
        var lt = lq.toQuery().and("projectId", project).and("deleted", false);
        if (!i.getSpaceIds().isEmpty()) lt.in("spaceId", i.getSpaceIds());
        if (i.getSourceType() != null) lt.and("sourceType", i.getSourceType());
        if (i.getSourceId() != null) lt.and("sourceId", i.getSourceId());
        if (i.getFullName() != null)
            lq.getTerms()
                    .add(
                            SqlTerm.of(
                                    "cast(line_snapshot_json as jsonb)->>'fullName' like ?",
                                    "%" + i.getFullName() + "%"));
        if (!i.getSubjectIds().isEmpty())
            lq.getTerms()
                    .add(
                            SqlTerm.of(
                                    "cast(line_snapshot_json as jsonb)->>'subjectId' in ("
                                            + String.join(
                                                    ",",
                                                    Collections.nCopies(
                                                            i.getSubjectIds().size(), "?"))
                                            + ")",
                                    i.getSubjectIds().toArray()));
        if (i.getSubjectGroups() != null && !i.getSubjectGroups().isEmpty()) {
            var parts = new ArrayList<String>();
            var values = new ArrayList<Object>();
            for (var group : i.getSubjectGroups()) {
                parts.add(
                        "(cast(line_snapshot_json as jsonb)->>'subjectId' is not distinct from ?"
                                + " and cast(line_snapshot_json as jsonb)->>'subjectName' is not"
                                + " distinct from ?)");
                values.add(group.value());
                values.add(group.label());
            }
            lq.getTerms().add(SqlTerm.of("(" + String.join(" or ", parts) + ")", values.toArray()));
        }
        var related = lines.nativeQuery().select("documentId").setParam(lq).getSql();
        q.getTerms().add(SqlTerm.of("id in (" + related.getSql() + ")", related.getParameters()));
        var sql =
                documents
                        .nativeQuery()
                        .select(
                                "id",
                                "projectId",
                                "documentNumber",
                                "originalDocumentId",
                                "previousDocumentId",
                                "buyerName",
                                "buyerJson",
                                "sellerTaxNum",
                                "companyName",
                                "customerName",
                                "invoiceType",
                                "issuedAt",
                                "status",
                                "amount",
                                "netAmount",
                                "taxAmount",
                                "modifierName",
                                "remark")
                        .setParam(q)
                        .getSql();
        return new Sql(sql.getSql(), sql.getParameters());
    }

    private Flux<Map<String, Object>> select(String sql, List<Object> params) {
        return database.sql()
                .reactive()
                .select(SqlRequests.of(sql, params.toArray()), ResultWrappers.map());
    }

    private Map<String, Object> view(Map<String, Object> row, Map<String, String> projects) {
        var result = new LinkedHashMap<String, Object>(row);
        var buyer = JSON.parseObject(Objects.toString(result.remove("buyerJson"), "{}"));
        result.put("buyerTaxNum", buyer.getString("taxNum"));
        result.put("projectName", projects.get(Objects.toString(row.get("projectId"))));
        result.put("invoiceNo", row.get("documentNumber"));
        result.put("invoiceDate", row.get("issuedAt"));
        result.put("documentId", row.get("id"));
        result.put(
                "ticketColor",
                new BigDecimal(row.get("amount").toString()).signum() < 0 ? "RED" : "BLUE");
        var amounts = new LinkedHashMap<String, String>();
        amounts.put("invoicedAmount", money(row.get("amount"), 2));
        amounts.put("taxAmount", money(row.get("taxAmount"), 2));
        amounts.put("netAmount", money(row.get("netAmount"), 2));
        amounts.put("differenceAmount", money(row.get("difference"), 2));
        amounts.put("outputTax", money(row.get("output"), 6));
        result.put("amounts", amounts);
        return result;
    }

    private Map<String, String> amounts(Map<String, Object> r) {
        return Map.of(
                "invoicedAmount",
                money(r.get("gross"), 2),
                "netAmount",
                money(r.get("net"), 2),
                "taxAmount",
                money(r.get("tax"), 2),
                "differenceAmount",
                money(r.get("difference"), 2),
                "outputTax",
                money(r.get("output"), 6));
    }

    private static String money(Object value, int scale) {
        return new BigDecimal(Objects.toString(value, "0"))
                .setScale(scale, java.math.RoundingMode.HALF_UP)
                .toPlainString();
    }

    private record Sql(String sql, Object[] params) {}
}
