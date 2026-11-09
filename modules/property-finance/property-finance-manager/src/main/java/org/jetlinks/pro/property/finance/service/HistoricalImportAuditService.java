package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.audit;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.receivable.ReceivableView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.*;

import java.time.LocalDate;
import java.util.*;

/** 导入批次/明细按钮审核。暂存与正式应收分离，发布与日志在同一事务内完成。 */
@Service
@RequiredArgsConstructor
public class HistoricalImportAuditService {
    private static final AssetType SPACE = AssetType.of("space");
    private final FinanceScope scope;
    private final org.springframework.transaction.ReactiveTransactionManager transactions;
    private final FinancialPeriodGate periodGate;
    private final ReactiveRepository<ReceivableImportEntity, String> records;
    private final ReactiveRepository<ReceivableImportLineEntity, String> drafts;
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final ReactiveRepository<ReceivableLogEntity, String> logs;

    public Mono<PagerResult<LineView>> lines(
            String project, String recordId, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(record(project, recordId, false))
                .then(
                        Mono.defer(
                                () -> {
                                    var query = SubjectService.page(input);
                                    query.setForUpdate(false);
                                    query.toNestQuery(
                                            n ->
                                                    n.and("projectId", project)
                                                            .and("importId", recordId)
                                                            .and("deleted", false));
                                    query.toQuery().orderByAsc("lineNumber");
                                    // 自定义明细查询仍通过统一空间资产权限过滤，不依赖前端筛选。
                                    return FinanceSpaceQuery.inject(
                                                    query, drafts, CrudAssetPermission.read)
                                            .flatMap(
                                                    q ->
                                                            org.hswebframework.web.crud.query
                                                                    .QueryHelper
                                                                    .transformPageResult(
                                                                            org.hswebframework.web
                                                                                    .crud.query
                                                                                    .QueryHelper
                                                                                    .queryPager(
                                                                                            q,
                                                                                            drafts
                                                                                                    ::createQuery),
                                                                            rows ->
                                                                                    Mono.just(
                                                                                            rows
                                                                                                    .stream()
                                                                                                    .map(
                                                                                                            LineView
                                                                                                                    ::of)
                                                                                                    .toList())));
                                }));
    }

    @Transactional
    public Mono<ReceivableImportEntity> decide(
            String project, String recordId, Decision input, boolean approve) {
        if (input == null
                || input.version() == null
                || input.reason() == null
                || input.reason().isBlank()
                || input.reason().length() > 1000
                || input.items() != null && input.items().size() > 100)
            return Mono.error(error("historical_review_invalid", 400));
        var items = input.items() == null ? List.<Item>of() : input.items();
        if (items.stream()
                        .anyMatch(
                                item -> item == null || item.id() == null || item.version() == null)
                || items.stream().map(Item::id).distinct().count() != items.size())
            return Mono.error(error("historical_review_invalid", 400));
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                periodGate
                                        .lock(project)
                                        .flatMap(
                                                control ->
                                                        record(project, recordId, true)
                                                                .flatMap(
                                                                        record -> {
                                                                            if (!Objects.equals(
                                                                                            input
                                                                                                    .version(),
                                                                                            record
                                                                                                    .getVersion())
                                                                                    || !"PENDING"
                                                                                            .equals(
                                                                                                    record
                                                                                                            .getStatus()))
                                                                                return Mono.error(
                                                                                        error(
                                                                                                "version_conflict",
                                                                                                409));
                                                                            var locking =
                                                                                    new QueryParamEntity();
                                                                            locking.setForUpdate(
                                                                                    true);
                                                                            var query =
                                                                                    drafts.createQuery()
                                                                                            .setParam(
                                                                                                    locking)
                                                                                            .where(
                                                                                                    "projectId",
                                                                                                    project)
                                                                                            .and(
                                                                                                    "importId",
                                                                                                    recordId)
                                                                                            .and(
                                                                                                    "deleted",
                                                                                                    false)
                                                                                            .and(
                                                                                                    "status",
                                                                                                    "PENDING");
                                                                            if (!items.isEmpty())
                                                                                query.in(
                                                                                        "id",
                                                                                        items
                                                                                                .stream()
                                                                                                .map(
                                                                                                        Item
                                                                                                                ::id)
                                                                                                .toList());
                                                                            return query.fetch()
                                                                                    .collectList()
                                                                                    .flatMap(
                                                                                            selected -> {
                                                                                                if (selected
                                                                                                                .isEmpty()
                                                                                                        || !items
                                                                                                                        .isEmpty()
                                                                                                                && selected
                                                                                                                                .size()
                                                                                                                        != items
                                                                                                                                .size())
                                                                                                    return Mono
                                                                                                            .error(
                                                                                                                    error(
                                                                                                                            "historical_review_invalid",
                                                                                                                            409));
                                                                                                for (var
                                                                                                        row :
                                                                                                                selected) {
                                                                                                    var
                                                                                                            expected =
                                                                                                                    items
                                                                                                                            .stream()
                                                                                                                            .filter(
                                                                                                                                    item ->
                                                                                                                                            item.id()
                                                                                                                                                    .equals(
                                                                                                                                                            row
                                                                                                                                                                    .getId()))
                                                                                                                            .findFirst();
                                                                                                    if (expected
                                                                                                                    .isPresent()
                                                                                                            && !Objects
                                                                                                                    .equals(
                                                                                                                            expected.get()
                                                                                                                                    .version(),
                                                                                                                            row
                                                                                                                                    .getVersion()))
                                                                                                        return Mono
                                                                                                                .error(
                                                                                                                        error(
                                                                                                                                "version_conflict",
                                                                                                                                409));
                                                                                                }
                                                                                                // 全部资产与会计期间验证通过后才开始逐行发布；混合权限不允许部分成功。
                                                                                                return Flux
                                                                                                        .fromIterable(
                                                                                                                selected)
                                                                                                        .concatMap(
                                                                                                                row ->
                                                                                                                        AssetsHolder
                                                                                                                                .assertPermission(
                                                                                                                                        SPACE,
                                                                                                                                        row
                                                                                                                                                .getSpaceId(),
                                                                                                                                        CrudAssetPermission
                                                                                                                                                .save)
                                                                                                                                .then(
                                                                                                                                        approve
                                                                                                                                                ? periodGate
                                                                                                                                                        .assertOpen(
                                                                                                                                                                control,
                                                                                                                                                                LocalDate
                                                                                                                                                                        .now())
                                                                                                                                                        .then(
                                                                                                                                                                periodGate
                                                                                                                                                                        .assertOpen(
                                                                                                                                                                                control,
                                                                                                                                                                                LocalDate
                                                                                                                                                                                        .parse(
                                                                                                                                                                                                JSON.parseObject(
                                                                                                                                                                                                                row
                                                                                                                                                                                                                        .getPayloadJson(),
                                                                                                                                                                                                                ReceivableEntity
                                                                                                                                                                                                                        .class)
                                                                                                                                                                                                        .getCalcStartDate())))
                                                                                                                                                : Mono
                                                                                                                                                        .empty()))
                                                                                                        .thenMany(
                                                                                                                Flux
                                                                                                                        .fromIterable(
                                                                                                                                selected)
                                                                                                                        .concatMap(
                                                                                                                                row ->
                                                                                                                                        apply(
                                                                                                                                                project,
                                                                                                                                                row,
                                                                                                                                                input.reason()
                                                                                                                                                        .trim(),
                                                                                                                                                approve,
                                                                                                                                                auth)))
                                                                                                        .then(
                                                                                                                refresh(
                                                                                                                        record,
                                                                                                                        auth));
                                                                                            });
                                                                        })))
                .as(MonoTracer.create("/property/finance/receivable/import-review"));
    }

    private Mono<Void> apply(
            String project,
            ReceivableImportLineEntity draft,
            String reason,
            boolean approve,
            Authentication auth) {
        var bill = JSON.parseObject(draft.getPayloadJson(), ReceivableEntity.class);
        if (!Objects.equals(project, bill.getProjectId())
                || !Objects.equals(draft.getSpaceId(), bill.getSpaceId()))
            return Mono.error(error("historical_review_invalid", 409));
        Mono<Void> publish = Mono.empty();
        if (approve) {
            if (bill.getBillNo() == null || bill.getBillNo().isBlank())
                bill.setBillNo(
                        org.jetlinks.pro.property.finance.payment.FinanceDocumentNumbers.next(
                                "YS"));
            bill.setReviewState("APPROVED");
            bill.setReviewedOn(LocalDate.now().toString());
            bill.setReviewedBy(auth.getUser().getId());
            bill.setReviewedName(FinanceScope.operatorName(auth));
            bill.setReviewReason(reason);
            // 原账期唯一身份继续兜底并发重复导入；任意冲突令整次审核回滚。
            publish =
                    bills.createQuery()
                            .where("projectId", project)
                            .and("billKey", bill.getBillKey())
                            .count()
                            .flatMap(
                                    count ->
                                            count > 0
                                                    ? Mono.error(
                                                            org.jetlinks.pro.property.finance
                                                                    .receivable.HistoricalWorkbook
                                                                    .rowError(
                                                                            draft.getLineNumber(),
                                                                            "房屋/客户/科目/标准/日期",
                                                                            "historical_duplicate_file"))
                                                    : org.jetlinks.pro.tenant.holder.TenantHolder
                                                            .currentId()
                                                            .defaultIfEmpty("")
                                                            .flatMap(
                                                                    tenant -> {
                                                                        bill.setTenantId(tenant);
                                                                        return bills.insert(bill);
                                                                    })
                                                            .then(
                                                                    logs.insert(
                                                                            HistoricalReceivableService
                                                                                    .log(
                                                                                            bill,
                                                                                            auth)))
                                                            .then());
        }
        var decision = approve ? "APPROVED" : "REJECTED";
        return publish.then(
                        audit(drafts.createUpdate(), auth, draft.getVersion())
                                .set("status", decision)
                                .set("reviewReason", reason)
                                .set("reviewedBy", auth.getUser().getId())
                                .set("reviewedName", FinanceScope.operatorName(auth))
                                .set("reviewedAt", System.currentTimeMillis())
                                .where("id", draft.getId())
                                .and("projectId", project)
                                .and("version", draft.getVersion())
                                .and("status", "PENDING")
                                .execute()
                                .flatMap(SubjectService::changed))
                .then();
    }

    private Mono<ReceivableImportEntity> refresh(
            ReceivableImportEntity record, Authentication auth) {
        return Mono.zip(
                        drafts.createQuery()
                                .where("importId", record.getId())
                                .and("deleted", false)
                                .count(),
                        drafts.createQuery()
                                .where("importId", record.getId())
                                .and("deleted", false)
                                .and("status", "APPROVED")
                                .count(),
                        drafts.createQuery()
                                .where("importId", record.getId())
                                .and("deleted", false)
                                .and("status", "REJECTED")
                                .count())
                .flatMap(
                        counts -> {
                            long total = counts.getT1(),
                                    approved = counts.getT2(),
                                    rejected = counts.getT3();
                            String status =
                                    approved + rejected < total
                                            ? "PENDING"
                                            : approved > 0 ? "APPROVED" : "REJECTED";
                            return audit(records.createUpdate(), auth, record.getVersion())
                                    .set("status", status)
                                    .set("phase", status)
                                    .set("successCount", (int) approved)
                                    .set("rejectedCount", (int) rejected)
                                    .where("id", record.getId())
                                    .and("version", record.getVersion())
                                    .execute()
                                    .flatMap(SubjectService::changed)
                                    .then(records.findById(record.getId()));
                        });
    }

    /** 后台整批审核使用同一发布规则，每行事务避免上万行长事务；已完成行不会再次发布。 */
    Mono<Void> processLarge(
            String project,
            String id,
            String reason,
            boolean approve,
            java.util.function.Function<Integer, Mono<Void>> progress) {
        return scope.authenticated()
                .flatMap(
                        auth -> {
                            var checked = new int[1];
                            var processed = new int[1];
                            return drafts.createQuery()
                                    .where("importId", id)
                                    .and("deleted", false)
                                    .and("status", "PENDING")
                                    .fetch()
                                    .concatMap(
                                            row ->
                                                    AssetsHolder.assertPermission(
                                                                    SPACE,
                                                                    row.getSpaceId(),
                                                                    CrudAssetPermission.save)
                                                            .then(
                                                                    approve
                                                                            ? org.springframework
                                                                                    .transaction
                                                                                    .reactive
                                                                                    .TransactionalOperator
                                                                                    .create(
                                                                                            transactions)
                                                                                    .transactional(
                                                                                            periodGate
                                                                                                    .lock(
                                                                                                            project)
                                                                                                    .flatMap(
                                                                                                            control ->
                                                                                                                    periodGate
                                                                                                                            .assertOpen(
                                                                                                                                    control,
                                                                                                                                    LocalDate
                                                                                                                                            .now())
                                                                                                                            .then(
                                                                                                                                    periodGate
                                                                                                                                            .assertOpen(
                                                                                                                                                    control,
                                                                                                                                                    LocalDate
                                                                                                                                                            .parse(
                                                                                                                                                                    JSON.parseObject(
                                                                                                                                                                                    row
                                                                                                                                                                                            .getPayloadJson(),
                                                                                                                                                                                    ReceivableEntity
                                                                                                                                                                                            .class)
                                                                                                                                                                            .getCalcStartDate())))))
                                                                            : Mono.empty())
                                                            .then(
                                                                    ++checked[0] % 10 == 0
                                                                            ? progress.apply(
                                                                                    checked[0])
                                                                            : Mono.empty()),
                                            1)
                                    .then()
                                    .thenMany(
                                            Flux.defer(
                                                            () -> {
                                                                var q = new QueryParamEntity();
                                                                q.setPaging(true);
                                                                q.setPageSize(100);
                                                                q.toQuery()
                                                                        .orderByAsc("lineNumber");
                                                                return drafts.createQuery()
                                                                        .setParam(q)
                                                                        .where("importId", id)
                                                                        .and("deleted", false)
                                                                        .and("status", "PENDING")
                                                                        .fetch()
                                                                        .collectList()
                                                                        .flatMap(
                                                                                rows -> {
                                                                                    if (rows
                                                                                            .isEmpty())
                                                                                        return Mono
                                                                                                .just(
                                                                                                        true);
                                                                                    return Flux
                                                                                            .fromIterable(
                                                                                                    rows)
                                                                                            .concatMap(
                                                                                                    row ->
                                                                                                            org
                                                                                                                    .springframework
                                                                                                                    .transaction
                                                                                                                    .reactive
                                                                                                                    .TransactionalOperator
                                                                                                                    .create(
                                                                                                                            transactions)
                                                                                                                    .transactional(
                                                                                                                            periodGate
                                                                                                                                    .lock(
                                                                                                                                            project)
                                                                                                                                    .flatMap(
                                                                                                                                            control ->
                                                                                                                                                    record(
                                                                                                                                                                    project,
                                                                                                                                                                    id,
                                                                                                                                                                    true)
                                                                                                                                                            .flatMap(
                                                                                                                                                                    batch -> {
                                                                                                                                                                        if (!"REVIEWING"
                                                                                                                                                                                .equals(
                                                                                                                                                                                        batch
                                                                                                                                                                                                .getStatus()))
                                                                                                                                                                            return Mono
                                                                                                                                                                                    .error(
                                                                                                                                                                                            error(
                                                                                                                                                                                                    "version_conflict",
                                                                                                                                                                                                    409));
                                                                                                                                                                        var
                                                                                                                                                                                locking =
                                                                                                                                                                                        new QueryParamEntity();
                                                                                                                                                                        locking
                                                                                                                                                                                .setForUpdate(
                                                                                                                                                                                        true);
                                                                                                                                                                        return drafts.createQuery()
                                                                                                                                                                                .setParam(
                                                                                                                                                                                        locking)
                                                                                                                                                                                .where(
                                                                                                                                                                                        "id",
                                                                                                                                                                                        row
                                                                                                                                                                                                .getId())
                                                                                                                                                                                .and(
                                                                                                                                                                                        "status",
                                                                                                                                                                                        "PENDING")
                                                                                                                                                                                .fetchOne()
                                                                                                                                                                                .flatMap(
                                                                                                                                                                                        current ->
                                                                                                                                                                                                AssetsHolder
                                                                                                                                                                                                        .assertPermission(
                                                                                                                                                                                                                SPACE,
                                                                                                                                                                                                                current
                                                                                                                                                                                                                        .getSpaceId(),
                                                                                                                                                                                                                CrudAssetPermission
                                                                                                                                                                                                                        .save)
                                                                                                                                                                                                        .then(
                                                                                                                                                                                                                approve
                                                                                                                                                                                                                        ? periodGate
                                                                                                                                                                                                                                .assertOpen(
                                                                                                                                                                                                                                        control,
                                                                                                                                                                                                                                        LocalDate
                                                                                                                                                                                                                                                .now())
                                                                                                                                                                                                                                .then(
                                                                                                                                                                                                                                        periodGate
                                                                                                                                                                                                                                                .assertOpen(
                                                                                                                                                                                                                                                        control,
                                                                                                                                                                                                                                                        LocalDate
                                                                                                                                                                                                                                                                .parse(
                                                                                                                                                                                                                                                                        JSON.parseObject(
                                                                                                                                                                                                                                                                                        current
                                                                                                                                                                                                                                                                                                .getPayloadJson(),
                                                                                                                                                                                                                                                                                        ReceivableEntity
                                                                                                                                                                                                                                                                                                .class)
                                                                                                                                                                                                                                                                                .getCalcStartDate())))
                                                                                                                                                                                                                        : Mono
                                                                                                                                                                                                                                .empty())
                                                                                                                                                                                                        .then(
                                                                                                                                                                                                                apply(
                                                                                                                                                                                                                        project,
                                                                                                                                                                                                                        current,
                                                                                                                                                                                                                        reason,
                                                                                                                                                                                                                        approve,
                                                                                                                                                                                                                        auth)));
                                                                                                                                                                    }))),
                                                                                                    1)
                                                                                            .then(
                                                                                                    progress
                                                                                                            .apply(
                                                                                                                    processed[
                                                                                                                                    0] +=
                                                                                                                            rows
                                                                                                                                    .size()))
                                                                                            .thenReturn(
                                                                                                    false);
                                                                                })
                                                                        .flux();
                                                            })
                                                    .repeat()
                                                    .takeUntil(Boolean::booleanValue))
                                    .then(
                                            record(project, id, false)
                                                    .flatMap(batch -> refresh(batch, auth)))
                                    .then()
                                    .onErrorResume(
                                            failure ->
                                                    record(project, id, false)
                                                            .flatMap(batch -> refresh(batch, auth))
                                                            .then(Mono.error(failure)));
                        });
    }

    private Mono<ReceivableImportEntity> record(String project, String id, boolean lock) {
        var locking = new QueryParamEntity();
        locking.setForUpdate(lock);
        var query =
                records.createQuery()
                        .setParam(locking)
                        .where("id", id)
                        .and("projectId", project)
                        .and("deleted", false);
        return query.fetchOne()
                .switchIfEmpty(Mono.error(error("historical_review_not_found", 404)));
    }

    public record Item(String id, Long version) {}

    public record BatchDecision(List<Item> records, String reason) {}

    /** 同一次多批审核共用事务，固定批次锁顺序；任一失败不会留下先前批次的部分入账。 */
    @Transactional
    public Mono<List<ReceivableImportEntity>> decideBatches(
            String project, BatchDecision input, boolean approve) {
        if (input == null
                || input.records() == null
                || input.records().isEmpty()
                || input.records().size() > 100
                || input.records().stream()
                        .anyMatch(
                                item -> item == null || item.id() == null || item.version() == null)
                || input.records().stream().map(Item::id).distinct().count()
                        != input.records().size())
            return Mono.error(error("historical_review_invalid", 400));
        return Flux.fromIterable(
                        input.records().stream().sorted(Comparator.comparing(Item::id)).toList())
                .concatMap(
                        item ->
                                decide(
                                        project,
                                        item.id(),
                                        new Decision(item.version(), input.reason(), List.of()),
                                        approve))
                .collectList();
    }

    /** items为空审核本批全部待审核行，非空按明细审核；批次版本保护并发决策。 */
    public record Decision(Long version, String reason, List<Item> items) {}

    public record LineView(
            String id,
            Long version,
            int lineNumber,
            String status,
            String reviewReason,
            String reviewedName,
            Long reviewedAt,
            ReceivableView receivable) {
        static LineView of(ReceivableImportLineEntity row) {
            return new LineView(
                    row.getId(),
                    row.getVersion(),
                    row.getLineNumber(),
                    row.getStatus(),
                    row.getReviewReason(),
                    row.getReviewedName(),
                    row.getReviewedAt(),
                    ReceivableView.of(
                            JSON.parseObject(row.getPayloadJson(), ReceivableEntity.class), false));
        }
    }
}
