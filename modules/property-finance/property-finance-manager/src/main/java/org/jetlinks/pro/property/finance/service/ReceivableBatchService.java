package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.ReceivableEntity;
import org.jetlinks.pro.property.finance.receivable.ChineseMoney;
import org.jetlinks.pro.property.finance.receivable.ReceivablePolicy;
import org.jetlinks.pro.property.finance.setting.SettingRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 批量操作先完整校验，固定顺序锁账；打印模型读取当前财务事实并校验列表版本。 */
@Service
@RequiredArgsConstructor
public class ReceivableBatchService {
    private static final AssetType SPACE = AssetType.of("space");
    private final FinanceScope scope;
    private final FinancialPeriodGate periodGate;
    private final ReceivableService service;
    private final ReactiveRepository<ReceivableEntity, String> bills;

    public record Item(String id, Long version) {}

    public record Input(List<Item> items, String reason) {}

    public record NoticeLine(
            String id,
            String customerName,
            String subjectName,
            String start,
            String end,
            long months,
            String quantity,
            String price,
            String amount) {}

    public record Notice(
            String projectName,
            String fullName,
            List<String> customers,
            List<NoticeLine> rows,
            String total,
            String totalUppercase,
            String printedOn) {}

    @Transactional
    public Mono<Void> delete(String project, Input input) {
        // 在整批锁账前取期间锁；内部单笔 change 沿同一事务重入，不能只保护内部入口。
        return scope.project(project, CrudAssetPermission.delete)
                .then(periodGate.assertOpen(project, LocalDate.now()))
                .then(selected(project, input, CrudAssetPermission.delete, true))
                .flatMap(
                        rows -> {
                            if (input.reason() == null
                                    || input.reason().isBlank()
                                    || input.reason().length() > 500)
                                return Mono.error(error("receivable_change_invalid", 400));
                            rows.forEach(row -> ReceivablePolicy.assertAllowed(row, "DELETE"));
                            return Flux.fromIterable(rows)
                                    .concatMap(
                                            row ->
                                                    service.change(
                                                            project,
                                                            row.getId(),
                                                            new ReceivableService.ChangeInput(
                                                                    row.getVersion(),
                                                                    input.reason()),
                                                            "DELETE"))
                                    .then();
                        })
                .as(MonoTracer.create("/property/finance/receivable/batch-delete"));
    }

    @Transactional
    public Mono<Notice> notice(String project, Input input) {
        return scope.project(project, CrudAssetPermission.read)
                .flatMap(
                        info ->
                                selected(project, input, CrudAssetPermission.read, true)
                                        .map(
                                                rows -> {
                                                    var first = rows.get(0);
                                                    if (rows.stream()
                                                            .anyMatch(
                                                                    row ->
                                                                            !first.getSpaceId()
                                                                                            .equals(
                                                                                                    row
                                                                                                            .getSpaceId())
                                                                                    || Boolean.TRUE
                                                                                            .equals(
                                                                                                    row
                                                                                                            .getSplit())
                                                                                    || row
                                                                                                    .getArrears()
                                                                                            == null
                                                                                    || row.getArrears()
                                                                                                    .signum()
                                                                                            <= 0))
                                                        throw error("notice_scope", 400);
                                                    var total =
                                                            rows.stream()
                                                                    .map(
                                                                            ReceivableEntity
                                                                                    ::getArrears)
                                                                    .reduce(
                                                                            BigDecimal.ZERO,
                                                                            BigDecimal::add)
                                                                    .setScale(2);
                                                    return new Notice(
                                                            info.name(),
                                                            first.getFullName(),
                                                            rows.stream()
                                                                    .map(
                                                                            ReceivableEntity
                                                                                    ::getCustomerName)
                                                                    .distinct()
                                                                    .toList(),
                                                            rows.stream().map(this::line).toList(),
                                                            total.toPlainString(),
                                                            ChineseMoney.format(total),
                                                            LocalDate.now().toString());
                                                }));
    }

    private NoticeLine line(ReceivableEntity row) {
        var standard = SettingRules.standard(row.getSnapshotJson());
        var values = SettingRules.values(row.getSnapshotJson());
        var variable =
                Map.of(
                                "AREA",
                                "BILLING_AREA",
                                "PERSON",
                                "BILLING_PERSON",
                                "COUNT",
                                "CONTRACT_COUNT",
                                "WORKLOAD",
                                "WORKLOAD")
                        .get(standard.getBillingMethod());
        var historical = "HISTORICAL_IMPORT".equals(row.getSourceType());
        var quantity = variable == null || historical ? null : values.get(variable);
        long months =
                ChronoUnit.MONTHS.between(
                                java.time.YearMonth.from(LocalDate.parse(row.getCalcStartDate())),
                                java.time.YearMonth.from(LocalDate.parse(row.getCalcEndDate())))
                        + 1;
        return new NoticeLine(
                row.getId(),
                row.getCustomerName(),
                row.getSubjectName(),
                row.getCalcStartDate(),
                row.getCalcEndDate(),
                months,
                quantity == null ? "—" : quantity.toPlainString(),
                historical || standard.getPrice() == null
                        ? "—"
                        : standard.getPrice().toPlainString(),
                row.getArrears().toPlainString());
    }

    private Mono<List<ReceivableEntity>> selected(
            String project, Input input, CrudAssetPermission permission, boolean lock) {
        return Mono.defer(
                () -> {
                    if (input == null
                            || input.items() == null
                            || input.items().isEmpty()
                            || input.items().size() > 100)
                        return Mono.error(error("receivable_batch_invalid", 400));
                    var seen = new HashSet<String>();
                    for (var item : input.items())
                        if (item == null
                                || item.id() == null
                                || item.id().isBlank()
                                || item.version() == null
                                || !seen.add(item.id()))
                            return Mono.error(error("receivable_batch_invalid", 400));
                    return scope.project(project, permission)
                            .thenMany(
                                    Flux.fromIterable(
                                                    input.items().stream()
                                                            .sorted(Comparator.comparing(Item::id))
                                                            .toList())
                                            .concatMap(
                                                    item -> {
                                                        var query = new QueryParamEntity();
                                                        query.setForUpdate(lock);
                                                        return bills.createQuery()
                                                                .setParam(query)
                                                                .where("id", item.id())
                                                                .and("projectId", project)
                                                                .and("deleted", false)
                                                                .fetchOne()
                                                                .switchIfEmpty(
                                                                        Mono.error(
                                                                                error(
                                                                                        "receivable_not_found",
                                                                                        404)))
                                                                .flatMap(
                                                                        row ->
                                                                                AssetsHolder
                                                                                        .assertPermission(
                                                                                                SPACE,
                                                                                                row
                                                                                                        .getSpaceId(),
                                                                                                permission)
                                                                                        .then(
                                                                                                Mono
                                                                                                        .fromCallable(
                                                                                                                () -> {
                                                                                                                    if (!Objects
                                                                                                                            .equals(
                                                                                                                                    row
                                                                                                                                            .getVersion(),
                                                                                                                                    item
                                                                                                                                            .version()))
                                                                                                                        throw error(
                                                                                                                                "version_conflict",
                                                                                                                                409);
                                                                                                                    return row;
                                                                                                                })));
                                                    }))
                            .collectList();
                });
    }
}
