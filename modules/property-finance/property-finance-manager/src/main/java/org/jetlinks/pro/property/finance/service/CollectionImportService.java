package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.init;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.codec.digest.DigestUtils;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.crud.query.QueryHelper;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.*;
import org.springframework.core.io.buffer.*;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;

import java.time.*;
import java.util.*;

/** 四类收款共享全文件预检与按钮审批，暂存不触发任何财务流水。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CollectionImportService {
    private final FinanceScope scope;
    private final FinancialPeriodGate periods;
    private final CollectionImportSources sources;
    private final ReactiveRepository<CollectionImportEntity, String> records;
    private final ReactiveRepository<CollectionImportLineEntity, String> lines;
    private final ReactiveTransactionManager transactions;

    public Flux<DataBuffer> template(String project, String kind) {
        return authorize(project, kind, CrudAssetPermission.read)
                .then(sources.references(project))
                .flatMapMany(refs -> CollectionImportWorkbook.template(kind, refs));
    }

    public Mono<CollectionImportEntity> importFile(String project, String kind, FilePart file) {
        return authorize(project, kind, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth -> {
                            var record = new CollectionImportEntity();
                            record.setId(UUID.randomUUID().toString());
                            init(record, auth);
                            record.setProjectId(project);
                            record.setKind(kind);
                            record.setFileName(
                                    file == null
                                            ? ""
                                            : file.filename()
                                                    .substring(
                                                            0,
                                                            Math.min(
                                                                    255,
                                                                    file.filename().length())));
                            record.setStatus("PROCESSING");
                            record.setTotalCount(0);
                            record.setSuccessCount(0);
                            record.setRejectedCount(0);
                            record.setErrorCount(0);
                            record.setOperatorId(auth.getUser().getId());
                            record.setOperatorName(FinanceScope.operatorName(auth));
                            // 批次独立提交；全表事务回滚后仍保留用户可检索的失败行号/字段。
                            return records.insert(record)
                                    .then(
                                            Mono.defer(
                                                    () ->
                                                            read(file)
                                                                    .flatMap(
                                                                            bytes ->
                                                                                    Mono
                                                                                            .fromCallable(
                                                                                                    () ->
                                                                                                            CollectionImportWorkbook
                                                                                                                    .parse(
                                                                                                                            bytes,
                                                                                                                            kind))
                                                                                            .subscribeOn(
                                                                                                    Schedulers
                                                                                                            .boundedElastic()))
                                                                    .flatMap(
                                                                            rows ->
                                                                                    records.createUpdate()
                                                                                            .set(
                                                                                                    "totalCount",
                                                                                                    rows
                                                                                                            .size())
                                                                                            .where(
                                                                                                    "id",
                                                                                                    record
                                                                                                            .getId())
                                                                                            .execute()
                                                                                            .then(
                                                                                                    stage(
                                                                                                            project,
                                                                                                            kind,
                                                                                                            record,
                                                                                                            rows,
                                                                                                            auth)))
                                                                    .onErrorResume(
                                                                            failure ->
                                                                                    failed(
                                                                                            record,
                                                                                            failure))
                                                                    .then(
                                                                            record(
                                                                                    project,
                                                                                    kind,
                                                                                    record.getId(),
                                                                                    false))));
                        })
                .as(MonoTracer.create("property.finance.collection-import.stage"));
    }

    private Mono<byte[]> read(FilePart file) {
        if (file == null
                || file.filename().length() > 255
                || !file.filename().toLowerCase(Locale.ROOT).matches(".*\\.(xlsx|xls)$"))
            return Mono.error(
                    CollectionImportWorkbook.rowError(1, "文件", "collection_file_invalid"));
        return DataBufferUtils.join(file.content(), 10 * 1024 * 1024)
                .switchIfEmpty(
                        Mono.error(
                                CollectionImportWorkbook.rowError(
                                        1, "文件", "collection_file_invalid")))
                .map(
                        buffer -> {
                            try {
                                var bytes = new byte[buffer.readableByteCount()];
                                buffer.read(bytes);
                                return bytes;
                            } finally {
                                DataBufferUtils.release(buffer);
                            }
                        });
    }

    private Mono<Void> stage(
            String project,
            String kind,
            CollectionImportEntity record,
            List<CollectionImportWorkbook.Line> rows,
            Authentication auth) {
        var work =
                periods.lock(project)
                        .flatMapMany(
                                control ->
                                        Flux.fromIterable(rows)
                                                .concatMap(
                                                        row ->
                                                                periods.assertOpen(
                                                                                control,
                                                                                LocalDateTime.parse(
                                                                                                row.payload()
                                                                                                        .getPaidAt())
                                                                                        .toLocalDate())
                                                                        .onErrorMap(
                                                                                BusinessException
                                                                                        .class,
                                                                                e ->
                                                                                        CollectionImportWorkbook
                                                                                                .rowError(
                                                                                                        row
                                                                                                                .row(),
                                                                                                        "收款时间",
                                                                                                        "period_closed"))
                                                                        .then(
                                                                                prepareDraft(
                                                                                        project,
                                                                                        kind,
                                                                                        record
                                                                                                .getId(),
                                                                                        row,
                                                                                        auth))))
                        .collectList()
                        // 收齐并验证所有行后才执行第一条草稿insert，末行失败不会部分暂存。
                        .flatMapMany(drafts -> Flux.fromIterable(drafts).concatMap(lines::insert))
                        .then(
                                records.createUpdate()
                                        .set("status", "PENDING")
                                        .set("finishTime", System.currentTimeMillis())
                                        .where("id", record.getId())
                                        .execute())
                        .then();
        return atomic(work);
    }

    private Mono<CollectionImportLineEntity> prepareDraft(
            String project,
            String kind,
            String recordId,
            CollectionImportWorkbook.Line row,
            Authentication auth) {
        var key = sourceKey(project, kind, row.payload().getExternalNo());
        return lines.createQuery()
                .where("sourceKey", key)
                .count()
                .flatMap(
                        count -> {
                            if (count > 0)
                                return Mono.error(
                                        CollectionImportWorkbook.rowError(
                                                row.row(), "外部收款编号", "collection_duplicate"));
                            return sources.prepare(project, kind, row.payload(), row.row())
                                    .map(
                                            prepared -> {
                                                var draft = new CollectionImportLineEntity();
                                                draft.setId(UUID.randomUUID().toString());
                                                init(draft, auth);
                                                draft.setProjectId(project);
                                                draft.setImportId(recordId);
                                                draft.setLineNumber(row.row());
                                                draft.setSourceKey(key);
                                                draft.setStatus("PENDING");
                                                setPayload(draft, prepared.payload());
                                                return draft;
                                            });
                        });
    }

    private static void setPayload(CollectionImportLineEntity row, CollectionImportInput payload) {
        row.setPayloadJson(JSON.toJSONString(payload));
        row.setSpaceId(payload.getSpaceId());
        row.setFullName(payload.getFullName());
        row.setCustomerName(payload.getCustomerName());
    }

    private static String sourceKey(String project, String kind, String external) {
        return DigestUtils.sha256Hex(JSON.toJSONString(List.of(project, kind, external)));
    }

    private Mono<Void> failed(CollectionImportEntity record, Throwable failure) {
        log.warn("Collection import {} failed before staging completed", record.getId(), failure);
        var message =
                failure instanceof BusinessException b
                        ? org.hswebframework.web.i18n.LocaleUtils.resolveMessage(
                                b.getMessage(),
                                b.getMessage() + " " + Arrays.toString(b.getArgs()),
                                b.getArgs())
                        : org.hswebframework.web.i18n.LocaleUtils.resolveMessage(
                                "error.property_finance_collection_failed",
                                "error.property_finance_collection_failed");
        return records.createUpdate()
                .set("status", "FAILED")
                .set("errorCount", 1)
                .set("errorMessage", message.substring(0, Math.min(8000, message.length())))
                .set("finishTime", System.currentTimeMillis())
                .where("id", record.getId())
                .execute()
                .then();
    }

    public Mono<PagerResult<CollectionImportEntity>> records(
            String project, String kind, QueryParamEntity input) {
        return authorize(project, kind, CrudAssetPermission.read)
                .then(
                        Mono.defer(
                                () -> {
                                    var q = SubjectService.page(input);
                                    q.toNestQuery(
                                            n ->
                                                    n.and("projectId", project)
                                                            .and("kind", kind)
                                                            .and("deleted", false));
                                    q.toQuery().orderByDesc("createTime");
                                    return QueryHelper.queryPager(q, records::createQuery);
                                }));
    }

    public Mono<PagerResult<LineView>> lines(
            String project, String kind, String record, QueryParamEntity input) {
        return authorize(project, kind, CrudAssetPermission.read)
                .then(record(project, kind, record, false))
                .then(
                        Mono.defer(
                                () -> {
                                    var q = SubjectService.page(input);
                                    q.toNestQuery(
                                            n ->
                                                    n.and("projectId", project)
                                                            .and("importId", record)
                                                            .and("deleted", false));
                                    q.toQuery().orderByAsc("lineNumber");
                                    return FinanceSpaceQuery.inject(
                                                    q, lines, CrudAssetPermission.read)
                                            .flatMap(
                                                    secured ->
                                                            QueryHelper.transformPageResult(
                                                                    QueryHelper.queryPager(
                                                                            secured,
                                                                            lines::createQuery),
                                                                    rows ->
                                                                            Mono.just(
                                                                                    rows.stream()
                                                                                            .map(
                                                                                                    LineView
                                                                                                            ::of)
                                                                                            .toList())));
                                }));
    }

    /** 编辑同样锁项目期间与批次，且重做字段、引用、资产、金额预检；已审批行不可编辑。 */
    public Mono<EditResult> edit(
            String project, String kind, String recordId, String lineId, Edit input) {
        if (input == null
                || input.version() == null
                || input.lineVersion() == null
                || input.payload() == null)
            return Mono.error(error("collection_review_invalid", 400));
        return authorize(project, kind, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                atomic(
                                        periods.lock(project)
                                                .flatMap(
                                                        control ->
                                                                editLocked(
                                                                        project, kind, recordId,
                                                                        lineId, input, control,
                                                                        auth))));
    }

    private Mono<EditResult> editLocked(
            String project,
            String kind,
            String recordId,
            String lineId,
            Edit input,
            FinancialPeriodControlEntity control,
            Authentication auth) {
        return record(project, kind, recordId, true)
                .flatMap(
                        record -> {
                            if (!Objects.equals(record.getVersion(), input.version())
                                    || !"PENDING".equals(record.getStatus()))
                                return Mono.error(error("version_conflict", 409));
                            return lockedLines(project, recordId, List.of(lineId))
                                    .flatMap(
                                            rows -> {
                                                if (rows.size() != 1)
                                                    return Mono.error(
                                                            error("collection_not_found", 404));
                                                return editLine(
                                                        project,
                                                        kind,
                                                        record,
                                                        rows.get(0),
                                                        input,
                                                        control,
                                                        auth);
                                            });
                        });
    }

    private Mono<EditResult> editLine(
            String project,
            String kind,
            CollectionImportEntity record,
            CollectionImportLineEntity row,
            Edit input,
            FinancialPeriodControlEntity control,
            Authentication auth) {
        if (!"PENDING".equals(row.getStatus())
                || !Objects.equals(input.lineVersion(), row.getVersion()))
            return Mono.error(error("version_conflict", 409));
        var old = JSON.parseObject(row.getPayloadJson(), CollectionImportInput.class);
        if (!Objects.equals(old.getExternalNo(), input.payload().getExternalNo()))
            return Mono.error(error("collection_source_immutable", 400));
        return periods.assertOpen(control, LocalDateTime.parse(old.getPaidAt()).toLocalDate())
                .then(
                        AssetsHolder.assertPermission(
                                AssetType.of("space"), row.getSpaceId(), CrudAssetPermission.save))
                .then(
                        Mono.defer(
                                () -> {
                                    CollectionImportWorkbook.validate(
                                            kind, input.payload(), row.getLineNumber());
                                    return periods.assertOpen(
                                            control,
                                            LocalDateTime.parse(input.payload().getPaidAt())
                                                    .toLocalDate());
                                }))
                .then(sources.prepare(project, kind, input.payload(), row.getLineNumber()))
                .flatMap(
                        prepared ->
                                uniqueReceivable(kind, record.getId(), row, prepared.payload())
                                        .then(persistEdit(record, row, prepared.payload(), auth)));
    }

    private Mono<Void> uniqueReceivable(
            String kind,
            String recordId,
            CollectionImportLineEntity edited,
            CollectionImportInput payload) {
        if (!"RECEIVED".equals(kind)) return Mono.empty();
        return lines.createQuery()
                .where("importId", recordId)
                .and("deleted", false)
                .fetch()
                .collectList()
                .flatMap(
                        rows ->
                                rows.stream()
                                                .anyMatch(
                                                        row ->
                                                                !row.getId().equals(edited.getId())
                                                                        && Objects.equals(
                                                                                payload
                                                                                        .getReceivableId(),
                                                                                JSON.parseObject(
                                                                                                row
                                                                                                        .getPayloadJson(),
                                                                                                CollectionImportInput
                                                                                                        .class)
                                                                                        .getReceivableId()))
                                        ? Mono.error(
                                                CollectionImportWorkbook.rowError(
                                                        edited.getLineNumber(),
                                                        "应收明细ID",
                                                        "collection_receivable_duplicate"))
                                        : Mono.empty());
    }

    private Mono<EditResult> persistEdit(
            CollectionImportEntity record,
            CollectionImportLineEntity row,
            CollectionImportInput payload,
            Authentication auth) {
        setPayload(row, payload);
        return lines.createUpdate()
                .set("payloadJson", row.getPayloadJson())
                .set("spaceId", row.getSpaceId())
                .set("fullName", row.getFullName())
                .set("customerName", row.getCustomerName())
                .set("version", row.getVersion() + 1)
                .set("modifyTime", System.currentTimeMillis())
                .set("modifierId", auth.getUser().getId())
                .set("modifierName", FinanceScope.operatorName(auth))
                .where("id", row.getId())
                .and("version", row.getVersion())
                .and("status", "PENDING")
                .execute()
                .flatMap(
                        count ->
                                count == 1
                                        ? bumpRecord(record, auth)
                                                .then(
                                                        Mono.zip(
                                                                records.findById(record.getId()),
                                                                lines.findById(row.getId())))
                                                .map(
                                                        tuple ->
                                                                new EditResult(
                                                                        tuple.getT1(),
                                                                        LineView.of(tuple.getT2())))
                                        : Mono.error(error("version_conflict", 409)));
    }

    public Mono<CollectionImportEntity> decide(
            String project, String kind, String recordId, Decision input, boolean approve) {
        if (!valid(input)) return Mono.error(error("collection_review_invalid", 400));
        return authorize(project, kind, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                atomic(
                                        periods.lock(project)
                                                .then(
                                                        decideLocked(
                                                                project, kind, recordId, input,
                                                                approve, auth))))
                .as(MonoTracer.create("property.finance.collection-import.review"));
    }

    /** 多批次审批在同一响应式事务中完成，任意一批冲突则全部回滚。 */
    public Mono<List<CollectionImportEntity>> decideMany(
            String project, String kind, BulkDecision input, boolean approve) {
        if (input == null
                || input.records() == null
                || input.records().isEmpty()
                || input.records().size() > 20
                || input.records().stream()
                        .anyMatch(i -> i == null || i.id() == null || i.version() == null)
                || input.records().stream().map(Item::id).distinct().count()
                        != input.records().size()
                || !valid(new Decision(0L, input.reason(), List.of())))
            return Mono.error(error("collection_review_invalid", 400));
        return authorize(project, kind, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                atomic(
                                        periods.lock(project)
                                                .thenMany(
                                                        Flux.fromIterable(
                                                                        input.records().stream()
                                                                                .sorted(
                                                                                        Comparator
                                                                                                .comparing(
                                                                                                        Item
                                                                                                                ::id))
                                                                                .toList())
                                                                .concatMap(
                                                                        item ->
                                                                                decideLocked(
                                                                                        project,
                                                                                        kind,
                                                                                        item.id(),
                                                                                        new Decision(
                                                                                                item
                                                                                                        .version(),
                                                                                                input
                                                                                                        .reason(),
                                                                                                List
                                                                                                        .of()),
                                                                                        approve,
                                                                                        auth)))
                                                .collectList()));
    }

    private Mono<CollectionImportEntity> decideLocked(
            String project,
            String kind,
            String recordId,
            Decision input,
            boolean approve,
            Authentication auth) {
        return record(project, kind, recordId, true)
                .flatMap(
                        record -> {
                            if (!Objects.equals(input.version(), record.getVersion())
                                    || !"PENDING".equals(record.getStatus()))
                                return Mono.error(error("version_conflict", 409));
                            var selected = input.items() == null ? List.<Item>of() : input.items();
                            return lockedLines(
                                            project,
                                            recordId,
                                            selected.stream().map(Item::id).toList())
                                    .flatMap(
                                            rows -> {
                                                if (rows.isEmpty()
                                                        || !selected.isEmpty()
                                                                && rows.size() != selected.size())
                                                    return Mono.error(
                                                            error(
                                                                    "collection_review_invalid",
                                                                    409));
                                                for (var row : rows)
                                                    if (!"PENDING".equals(row.getStatus())
                                                            || selected.stream()
                                                                    .filter(
                                                                            i ->
                                                                                    i.id().equals(
                                                                                                    row
                                                                                                            .getId()))
                                                                    .anyMatch(
                                                                            i ->
                                                                                    !Objects.equals(
                                                                                            i
                                                                                                    .version(),
                                                                                            row
                                                                                                    .getVersion())))
                                                        return Mono.error(
                                                                error("version_conflict", 409));
                                                return Flux.fromIterable(rows)
                                                        .concatMap(
                                                                row ->
                                                                        AssetsHolder
                                                                                .assertPermission(
                                                                                        AssetType
                                                                                                .of(
                                                                                                        "space"),
                                                                                        row
                                                                                                .getSpaceId(),
                                                                                        CrudAssetPermission
                                                                                                .save))
                                                        .thenMany(
                                                                Flux.fromIterable(rows)
                                                                        .concatMap(
                                                                                row ->
                                                                                        reviewLine(
                                                                                                project,
                                                                                                kind,
                                                                                                row,
                                                                                                input
                                                                                                        .reason(),
                                                                                                approve,
                                                                                                auth)))
                                                        .then(refreshRecord(record, auth));
                                            });
                        });
    }

    private Mono<Void> reviewLine(
            String project,
            String kind,
            CollectionImportLineEntity row,
            String reason,
            boolean approve,
            Authentication auth) {
        var payload = JSON.parseObject(row.getPayloadJson(), CollectionImportInput.class);
        Mono<String> publication =
                approve
                        ? sources.prepare(project, kind, payload, row.getLineNumber())
                                .flatMap(
                                        prepared ->
                                                sources.publish(
                                                        project,
                                                        kind,
                                                        "import_" + row.getId(),
                                                        prepared.payload()))
                        : Mono.just("");
        return publication.flatMap(
                result ->
                        lines.createUpdate()
                                .set("status", approve ? "APPROVED" : "REJECTED")
                                .set("resultId", result)
                                .set("reviewReason", reason)
                                .set("reviewedBy", auth.getUser().getId())
                                .set("reviewedName", FinanceScope.operatorName(auth))
                                .set("reviewedAt", System.currentTimeMillis())
                                .set("version", row.getVersion() + 1)
                                .where("id", row.getId())
                                .and("status", "PENDING")
                                .and("version", row.getVersion())
                                .execute()
                                .flatMap(
                                        count ->
                                                count == 1
                                                        ? Mono.empty()
                                                        : Mono.error(
                                                                error("version_conflict", 409))));
    }

    private Mono<CollectionImportEntity> refreshRecord(
            CollectionImportEntity record, Authentication auth) {
        return lines.createQuery()
                .where("importId", record.getId())
                .and("deleted", false)
                .fetch()
                .collectList()
                .flatMap(
                        rows -> {
                            int
                                    approved =
                                            (int)
                                                    rows.stream()
                                                            .filter(
                                                                    r ->
                                                                            "APPROVED"
                                                                                    .equals(
                                                                                            r
                                                                                                    .getStatus()))
                                                            .count(),
                                    rejected =
                                            (int)
                                                    rows.stream()
                                                            .filter(
                                                                    r ->
                                                                            "REJECTED"
                                                                                    .equals(
                                                                                            r
                                                                                                    .getStatus()))
                                                            .count();
                            String status =
                                    approved + rejected < rows.size()
                                            ? "PENDING"
                                            : approved == rows.size()
                                                    ? "APPROVED"
                                                    : rejected == rows.size()
                                                            ? "REJECTED"
                                                            : "COMPLETED";
                            return records.createUpdate()
                                    .set("status", status)
                                    .set("successCount", approved)
                                    .set("rejectedCount", rejected)
                                    .set("version", record.getVersion() + 1)
                                    .set("modifyTime", System.currentTimeMillis())
                                    .set("modifierId", auth.getUser().getId())
                                    .set("modifierName", FinanceScope.operatorName(auth))
                                    .where("id", record.getId())
                                    .and("version", record.getVersion())
                                    .execute()
                                    .flatMap(
                                            count ->
                                                    count == 1
                                                            ? records.findById(record.getId())
                                                            : Mono.error(
                                                                    error(
                                                                            "version_conflict",
                                                                            409)));
                        });
    }

    private Mono<Void> bumpRecord(CollectionImportEntity record, Authentication auth) {
        return records.createUpdate()
                .set("version", record.getVersion() + 1)
                .set("modifyTime", System.currentTimeMillis())
                .set("modifierId", auth.getUser().getId())
                .set("modifierName", FinanceScope.operatorName(auth))
                .where("id", record.getId())
                .and("version", record.getVersion())
                .execute()
                .flatMap(
                        count ->
                                count == 1
                                        ? Mono.empty()
                                        : Mono.error(error("version_conflict", 409)));
    }

    private Mono<List<CollectionImportLineEntity>> lockedLines(
            String project, String record, List<String> selected) {
        var q = new QueryParamEntity();
        q.setForUpdate(true);
        var query =
                lines.createQuery()
                        .setParam(q)
                        .where("projectId", project)
                        .and("importId", record)
                        .and("deleted", false);
        if (!selected.isEmpty()) query.in("id", selected);
        else query.and("status", "PENDING");
        return query.fetch()
                .take(501)
                .collectList()
                .flatMap(
                        rows ->
                                rows.size() > 500
                                        ? Mono.error(error("collection_limit", 400))
                                        : Mono.just(rows));
    }

    private Mono<CollectionImportEntity> record(
            String project, String kind, String id, boolean lock) {
        var q = new QueryParamEntity();
        q.setForUpdate(lock);
        return records.createQuery()
                .setParam(q)
                .where("id", id)
                .and("projectId", project)
                .and("kind", kind)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("collection_not_found", 404)));
    }

    private Mono<Void> authorize(String project, String kind, CrudAssetPermission permission) {
        return CollectionImportWorkbook.KINDS.contains(Objects.toString(kind, ""))
                ? scope.project(project, permission).then()
                : Mono.error(error("collection_kind_invalid", 400));
    }

    private <T> Mono<T> atomic(Mono<T> work) {
        return TransactionalOperator.create(transactions).transactional(work);
    }

    private static boolean valid(Decision input) {
        return input != null
                && input.version() != null
                && input.reason() != null
                && !input.reason().isBlank()
                && input.reason().length() <= 1000
                && (input.items() == null
                        || input.items().size() <= 100
                                && input.items().stream()
                                        .allMatch(
                                                i ->
                                                        i != null
                                                                && i.id() != null
                                                                && i.version() != null)
                                && input.items().stream().map(Item::id).distinct().count()
                                        == input.items().size());
    }

    public record Item(String id, Long version) {}

    public record Decision(Long version, String reason, List<Item> items) {}

    public record BulkDecision(List<Item> records, String reason) {}

    public record Edit(Long version, Long lineVersion, CollectionImportInput payload) {}

    public record EditResult(CollectionImportEntity record, LineView line) {}

    public record LineView(
            String id,
            Long version,
            int lineNumber,
            String status,
            String fullName,
            String customerName,
            String reviewReason,
            String reviewedName,
            Long reviewedAt,
            String resultId,
            CollectionImportInput payload) {
        static LineView of(CollectionImportLineEntity row) {
            return new LineView(
                    row.getId(),
                    row.getVersion(),
                    row.getLineNumber(),
                    row.getStatus(),
                    row.getFullName(),
                    row.getCustomerName(),
                    row.getReviewReason(),
                    row.getReviewedName(),
                    row.getReviewedAt(),
                    row.getResultId(),
                    JSON.parseObject(row.getPayloadJson(), CollectionImportInput.class));
        }
    }
}
