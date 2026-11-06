package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.*;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.organization.PropertyProjectInfo;
import org.jetlinks.pro.property.finance.calculation.ContractMonthAllocation;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.report.*;
import org.jetlinks.pro.property.finance.report.ContractReportResult.*;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import reactor.core.publisher.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 合同摘要与六项下钻共用冻结金额及同一资金来源；查询不触发算费或初始化账期。 */
@Service
@RequiredArgsConstructor
public class ContractReportService {
    public static final String RESOURCE = "property-finance-report-contract";
    private static final int LIMIT = 10000;
    private final FinanceScope scope;
    private final DocumentQueryScope visibility;
    private final ReactiveRepository<ContractMirrorEntity, String> contracts;
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final ContractReportSource source;
    private final ContractMonthAllocation allocation;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Mono<ContractReportResult> query(ContractReportInput input) {
        return load(input, "query")
                .map(snapshot -> result(input, snapshot, false))
                .as(MonoTracer.create("/property/finance/reports/contracts/query"));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Mono<ContractReportResult> exportData(ContractReportInput input) {
        return load(input, "export")
                .map(snapshot -> result(input, snapshot, true))
                .as(MonoTracer.create("/property/finance/reports/contracts/export"));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Mono<PagerResult<Detail>> details(
            ContractReportInput input, String contractId, String metric) {
        return Mono.defer(
                        () -> {
                            if (contractId == null
                                    || contractId.isBlank()
                                    || contractId.length() > 64
                                    || metric == null
                                    || !Amounts.METRICS.contains(metric))
                                return Mono.error(error("report_query_invalid", 400));
                            return load(input, "query")
                                    .map(
                                            snapshot ->
                                                    detailPage(
                                                            input,
                                                            snapshot.rows(),
                                                            contractId,
                                                            metric));
                        })
                .as(MonoTracer.create("/property/finance/reports/contracts/details"));
    }

    private static PagerResult<Detail> detailPage(
            ContractReportInput input, List<Computed> snapshot, String contractId, String metric) {
        var contract =
                snapshot.stream()
                        .filter(s -> s.contract().getId().equals(contractId))
                        .findFirst()
                        .orElseThrow(() -> error("contract_report_not_found", 404));
        var rows =
                contract.bills().stream()
                        .filter(b -> new BigDecimal(b.amounts().value(metric)).signum() != 0)
                        .map(b -> detail(b, metric))
                        .toList();
        return page(rows, input.getPageIndex(), input.getPageSize());
    }

    private Mono<Snapshot> load(ContractReportInput input, String action) {
        return Mono.defer(
                () -> {
                    if (input == null) return Mono.error(error("report_query_invalid", 400));
                    var month = input.validate();
                    return authorize(input.getProjectId(), action)
                            .flatMap(
                                    project ->
                                            TenantHolder.currentId()
                                                    .defaultIfEmpty("")
                                                    .flatMap(
                                                            tenant ->
                                                                    selectedContracts(
                                                                            input, tenant))
                                                    .flatMap(
                                                            selected ->
                                                                    computeSelected(
                                                                            input, month, selected))
                                                    .map(
                                                            rows ->
                                                                    new Snapshot(
                                                                            project.name(), rows)));
                });
    }

    private Mono<PropertyProjectInfo> authorize(String project, String action) {
        return scope.authenticated()
                .flatMap(
                        auth -> {
                            if (!auth.hasPermission(RESOURCE, action))
                                return Mono.error(error("permission_denied", 403));
                            return scope.project(project, CrudAssetPermission.read);
                        });
    }

    private Mono<List<Computed>> computeSelected(
            ContractReportInput input, YearMonth month, List<ContractMirrorEntity> selected) {
        return selectedBills(input, selected)
                .flatMap(
                        rows ->
                                authorizeAll(rows)
                                        .then(source.paid(rows, month))
                                        .map(paid -> compute(input, month, selected, rows, paid)));
    }

    private Mono<List<ContractMirrorEntity>> selectedContracts(
            ContractReportInput i, String tenant) {
        var q = bounded();
        q.toQuery()
                .and("projectId", i.getProjectId())
                .and("tenantId", tenant)
                .and("deleted", false);
        if (!i.getSpaceIds().isEmpty()) q.toQuery().in("spaceId", i.getSpaceIds());
        if (i.getKeyword() != null && !i.getKeyword().isBlank())
            q.toQuery()
                    .nest()
                    .like("code", "%" + i.getKeyword().trim() + "%")
                    .or()
                    .like("name", "%" + i.getKeyword().trim() + "%")
                    .or()
                    .like("customerName", "%" + i.getKeyword().trim() + "%")
                    .end();
        if (i.getStartDate() != null) q.toQuery().gte("startDate", i.getStartDate());
        if (i.getEndDate() != null) q.toQuery().lte("endDate", i.getEndDate());
        q.orderBy("code");
        q.orderBy("id");
        return visibility
                .inject(q, contracts, CrudAssetPermission.read)
                .flatMapMany(secured -> contracts.createQuery().setParam(secured).fetch())
                .collectList()
                .map(ContractReportService::withinLimit);
    }

    private Mono<List<ReceivableEntity>> selectedBills(
            ContractReportInput i, List<ContractMirrorEntity> selected) {
        if (selected.isEmpty()) return Mono.just(List.of());
        var q = bounded();
        q.toQuery()
                .and("projectId", i.getProjectId())
                .and("deleted", false)
                .in("contractCode", selected.stream().map(ContractMirrorEntity::getCode).toList())
                .nest()
                .isNull("split")
                .or()
                .is("split", false)
                .end();
        if (!i.getSubjectIds().isEmpty()) q.toQuery().in("subjectId", i.getSubjectIds());
        q.includes(
                "id",
                "contractCode",
                "spaceId",
                "fullName",
                "subjectName",
                "customerName",
                "calcStartDate",
                "calcEndDate",
                "contractReceivableAmount");
        q.orderBy("calcStartDate");
        q.orderBy("id");
        return bills.createQuery()
                .setParam(q)
                .fetch()
                .collectList()
                .map(ContractReportService::withinLimit);
    }

    /** 不能用当前合同房屋替代历史账单房屋；任一参与账单无权即拒绝整份合计。 */
    private Mono<Void> authorizeAll(List<ReceivableEntity> rows) {
        return AssetsHolder.assertPermission(
                AssetType.of("space"),
                rows.stream().map(ReceivableEntity::getSpaceId).distinct().toList(),
                CrudAssetPermission.read);
    }

    private List<Computed> compute(
            ContractReportInput i,
            YearMonth month,
            List<ContractMirrorEntity> selected,
            List<ReceivableEntity> rows,
            Map<String, ContractReportSource.Paid> paid) {
        // 账单只读取一次，保留同一REPEATABLE_READ快照及顺序，摘要和下钻不另走一套公式。
        var grouped = new LinkedHashMap<String, List<ComputedBill>>();
        for (var b : rows) {
            var p = paid.get(b.getId());
            if (p == null) throw error("contract_report_source_invalid", 409);
            Amounts amounts;
            try {
                amounts =
                        amounts(
                                allocation.statement(
                                        b.getContractReceivableAmount(),
                                        LocalDate.parse(b.getCalcStartDate()),
                                        LocalDate.parse(b.getCalcEndDate()),
                                        month,
                                        p.opening(),
                                        p.closing()));
            } catch (IllegalArgumentException | DateTimeException invalid) {
                throw error("contract_report_source_invalid", 409);
            }
            grouped.computeIfAbsent(b.getContractCode(), ignored -> new ArrayList<>())
                    .add(new ComputedBill(b, p, amounts));
        }
        var result = new ArrayList<Computed>();
        for (var c : selected) {
            var details = grouped.getOrDefault(c.getCode(), List.of());
            if (i.getSubjectIds().isEmpty() || !details.isEmpty())
                result.add(
                        new Computed(
                                c,
                                details,
                                details.stream()
                                        .map(ComputedBill::amounts)
                                        .reduce(Amounts.zero(), Amounts::plus)));
        }
        return result;
    }

    private static ContractReportResult result(
            ContractReportInput i, Snapshot snapshot, boolean export) {
        var summary =
                snapshot.rows().stream()
                        .map(Computed::amounts)
                        .reduce(Amounts.zero(), Amounts::plus);
        var rows =
                snapshot.rows().stream()
                        .map(
                                s -> {
                                    var c = s.contract();
                                    return new Row(
                                            c.getId(),
                                            c.getCode(),
                                            c.getName(),
                                            c.getCustomerName(),
                                            c.getSpaceId(),
                                            c.getFullName(),
                                            c.getStartDate(),
                                            c.getEndDate(),
                                            c.getLeaseArea(),
                                            s.amounts());
                                })
                        .toList();
        return new ContractReportResult(
                i.getProjectId(),
                snapshot.projectName(),
                i.getMonth(),
                summary,
                page(rows, export ? 0 : i.getPageIndex(), export ? LIMIT : i.getPageSize()));
    }

    private static Detail detail(ComputedBill b, String metric) {
        var bill = b.bill();
        return new Detail(
                bill.getId(),
                bill.getFullName(),
                bill.getSubjectName(),
                bill.getCustomerName(),
                bill.getCalcStartDate(),
                bill.getCalcEndDate(),
                bill.getContractReceivableAmount().setScale(2).toPlainString(),
                b.paid().opening().toPlainString(),
                b.paid().closing().toPlainString(),
                b.amounts().value(metric));
    }

    private static Amounts amounts(ContractMonthAllocation.Amounts a) {
        return new Amounts(
                a.openingReceivable().toPlainString(),
                a.openingAdvance().toPlainString(),
                a.currentReceivable().toPlainString(),
                a.currentNetReceived().toPlainString(),
                a.closingReceivable().toPlainString(),
                a.closingAdvance().toPlainString());
    }

    private static QueryParamEntity bounded() {
        var q = new QueryParamEntity();
        q.setPageSize(LIMIT + 1);
        return q;
    }

    private static <T> List<T> withinLimit(List<T> rows) {
        if (rows.size() > LIMIT) throw error("contract_report_limit", 400);
        return rows;
    }

    private static <T> PagerResult<T> page(List<T> rows, int index, int size) {
        int start = (int) Math.min((long) index * size, rows.size());
        var q = new QueryParamEntity();
        q.setPageIndex(index);
        q.setPageSize(size);
        return PagerResult.of(
                rows.size(), rows.subList(start, Math.min(start + size, rows.size())), q);
    }

    private record ComputedBill(
            ReceivableEntity bill, ContractReportSource.Paid paid, Amounts amounts) {}

    private record Computed(
            ContractMirrorEntity contract, List<ComputedBill> bills, Amounts amounts) {}

    private record Snapshot(String projectName, List<Computed> rows) {}
}
