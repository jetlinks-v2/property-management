package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.init;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.codec.digest.DigestUtils;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.*;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.*;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.buffer.*;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.Disposable;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** 持久任务单消费者，上传请求仅写文件和任务；生命周期负责有背压的扫描，不依赖浏览器保持连接。 */
@Component
@Slf4j
public class FinanceImportQueue implements SmartLifecycle, FinanceImportQueueMBean {
    private final java.util.concurrent.atomic.AtomicLong
            successes = new java.util.concurrent.atomic.AtomicLong(),
            failures = new java.util.concurrent.atomic.AtomicLong();
    private javax.management.ObjectName objectName;

    @Override
    public long getCompletedTasks() {
        return successes.get();
    }

    @Override
    public long getFailedTasks() {
        return failures.get();
    }

    private final FinanceScope scope;
    private final ReactiveRepository<FinanceImportTaskEntity, String> tasks;
    private final ReactiveRepository<CollectionImportEntity, String> collections;
    private final ReactiveRepository<ReceivableImportEntity, String> historical;
    private final ObjectProvider<CollectionImportService> collectionService;
    private final ObjectProvider<HistoricalReceivableService> historicalService;
    private final ObjectProvider<HistoricalImportAuditService> historicalAudit;
    private final ReactiveTransactionManager transactions;
    private final Path root;
    private final java.util.function.Function<String, Mono<Authentication>> authentications;

    @Value("${property.finance.import-auto-start:true}")
    private boolean autoStart = true;

    private Disposable subscription;
    private volatile boolean running;

    @org.springframework.beans.factory.annotation.Autowired
    public FinanceImportQueue(
            FinanceScope scope,
            ReactiveRepository<FinanceImportTaskEntity, String> tasks,
            ReactiveRepository<CollectionImportEntity, String> collections,
            ReactiveRepository<ReceivableImportEntity, String> historical,
            ObjectProvider<CollectionImportService> collectionService,
            ObjectProvider<HistoricalReceivableService> historicalService,
            ObjectProvider<HistoricalImportAuditService> historicalAudit,
            ReactiveTransactionManager transactions,
            @Value("${property.finance.import-directory:data/finance-imports}") String directory) {
        this(
                scope,
                tasks,
                collections,
                historical,
                collectionService,
                historicalService,
                historicalAudit,
                transactions,
                directory,
                ReactiveAuthenticationHolder::get);
    }

    FinanceImportQueue(
            FinanceScope scope,
            ReactiveRepository<FinanceImportTaskEntity, String> tasks,
            ReactiveRepository<CollectionImportEntity, String> collections,
            ReactiveRepository<ReceivableImportEntity, String> historical,
            ObjectProvider<CollectionImportService> collectionService,
            ObjectProvider<HistoricalReceivableService> historicalService,
            ObjectProvider<HistoricalImportAuditService> historicalAudit,
            ReactiveTransactionManager transactions,
            String directory,
            java.util.function.Function<String, Mono<Authentication>> authentications) {
        this.authentications = authentications;
        this.scope = scope;
        this.tasks = tasks;
        this.collections = collections;
        this.historical = historical;
        this.collectionService = collectionService;
        this.historicalService = historicalService;
        this.historicalAudit = historicalAudit;
        this.transactions = transactions;
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    public Mono<CollectionImportEntity> uploadCollection(
            String project, String kind, FilePart file) {
        if (!CollectionImportWorkbook.KINDS.contains(kind))
            return Mono.error(error("collection_kind_invalid", 400));
        return upload(project, kind, file)
                .flatMap(task -> collections.findById(task.getRecordId()));
    }

    public Mono<ReceivableImportEntity> uploadHistorical(String project, FilePart file) {
        return upload(project, "HISTORICAL", file)
                .flatMap(task -> historical.findById(task.getRecordId()));
    }

    private Mono<FinanceImportTaskEntity> upload(String project, String kind, FilePart file) {
        if (file == null
                || file.filename().length() > 255
                || !file.filename().toLowerCase(Locale.ROOT).matches(".*\\.(xlsx|xls)$"))
            return Mono.error(error("collection_file_invalid", 400));
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                tasks.createQuery()
                                        .in("state", List.of("QUEUED", "RUNNING"))
                                        .count()
                                        .flatMap(
                                                count -> {
                                                    if (count >= 20)
                                                        return Mono.error(
                                                                error(
                                                                        "collection_queue_full",
                                                                        429));
                                                    var task = new FinanceImportTaskEntity();
                                                    init(task, auth);
                                                    task.setId(UUID.randomUUID().toString());
                                                    task.setRecordId(task.getId());
                                                    task.setProjectId(project);
                                                    task.setKind(kind);
                                                    task.setOperation("UPLOAD");
                                                    task.setState("QUEUED");
                                                    task.setLeaseUntil(0L);
                                                    task.setFileName(file.filename());
                                                    var path = input(task);
                                                    return Mono.fromCallable(
                                                                    () -> {
                                                                        Files.createDirectories(
                                                                                root);
                                                                        return path;
                                                                    })
                                                            .subscribeOn(
                                                                    Schedulers.boundedElastic())
                                                            .flatMap(
                                                                    target -> {
                                                                        var size = new long[1];
                                                                        var digest =
                                                                                DigestUtils
                                                                                        .getSha256Digest();
                                                                        return DataBufferUtils
                                                                                .write(
                                                                                        file.content()
                                                                                                .doOnNext(
                                                                                                        buffer -> {
                                                                                                            size[
                                                                                                                            0] +=
                                                                                                                    buffer
                                                                                                                            .readableByteCount();
                                                                                                            if (size[
                                                                                                                            0]
                                                                                                                    > FinanceExcelRows
                                                                                                                            .MAX_BYTES)
                                                                                                                throw error(
                                                                                                                        "collection_file_invalid",
                                                                                                                        400);
                                                                                                            digest
                                                                                                                    .update(
                                                                                                                            buffer.asByteBuffer()
                                                                                                                                    .asReadOnlyBuffer());
                                                                                                        }),
                                                                                        target,
                                                                                        StandardOpenOption
                                                                                                .CREATE_NEW,
                                                                                        StandardOpenOption
                                                                                                .WRITE)
                                                                                .then(
                                                                                        Mono.defer(
                                                                                                () -> {
                                                                                                    if (size[
                                                                                                                    0]
                                                                                                            == 0)
                                                                                                        return Mono
                                                                                                                .error(
                                                                                                                        error(
                                                                                                                                "collection_file_invalid",
                                                                                                                                400));
                                                                                                    task
                                                                                                            .setSourceKey(
                                                                                                                    DigestUtils
                                                                                                                            .sha256Hex(
                                                                                                                                    project
                                                                                                                                            + ":"
                                                                                                                                            + kind
                                                                                                                                            + ":"
                                                                                                                                            + java
                                                                                                                                                    .util
                                                                                                                                                    .HexFormat
                                                                                                                                                    .of()
                                                                                                                                                    .formatHex(
                                                                                                                                                            digest
                                                                                                                                                                    .digest())));
                                                                                                    return Mono
                                                                                                            .just(
                                                                                                                    task);
                                                                                                }));
                                                                    })
                                                            .flatMap(
                                                                    value ->
                                                                            FinanceJobAuthority
                                                                                    .capture(
                                                                                            auth,
                                                                                            "collection_authority_invalid")
                                                                                    .doOnNext(
                                                                                            task
                                                                                                    ::setExecutorSnapshot))
                                                            .then(
                                                                    TenantHolder.currentId()
                                                                            .defaultIfEmpty(""))
                                                            .doOnNext(task::setTenantId)
                                                            .then(
                                                                    Mono.defer(
                                                                            () ->
                                                                                    tasks.createQuery()
                                                                                            .where(
                                                                                                    "sourceKey",
                                                                                                    task
                                                                                                            .getSourceKey())
                                                                                            .count()
                                                                                            .flatMap(
                                                                                                    duplicate ->
                                                                                                            duplicate
                                                                                                                            > 0
                                                                                                                    ? Mono
                                                                                                                            .error(
                                                                                                                                    error(
                                                                                                                                            "collection_file_duplicate",
                                                                                                                                            409))
                                                                                                                    : Mono
                                                                                                                            .empty())))
                                                            .then(
                                                                    Mono.defer(
                                                                            () ->
                                                                                    TransactionalOperator
                                                                                            .create(
                                                                                                    transactions)
                                                                                            .transactional(
                                                                                                    tasks.insert(
                                                                                                                    task)
                                                                                                            .then(
                                                                                                                    createBatch(
                                                                                                                            task,
                                                                                                                            auth)))))
                                                            .thenReturn(task)
                                                            .onErrorResume(
                                                                    failure ->
                                                                            deleteFiles(task)
                                                                                    .then(
                                                                                            Mono
                                                                                                    .error(
                                                                                                            failure)));
                                                }));
    }

    private Mono<Void> createBatch(FinanceImportTaskEntity task, Authentication auth) {
        if (task.getKind().equals("HISTORICAL")) {
            var row = new ReceivableImportEntity();
            init(row, auth);
            row.setId(task.getRecordId());
            row.setProjectId(task.getProjectId());
            row.setFileName(task.getFileName());
            row.setStatus("PROCESSING");
            row.setPhase("QUEUED");
            row.setProcessedCount(0);
            row.setTotalCount(0);
            row.setSuccessCount(0);
            row.setRejectedCount(0);
            row.setErrorCount(0);
            row.setOperatorId(auth.getUser().getId());
            row.setOperatorName(FinanceScope.operatorName(auth));
            return historical.insert(row).then();
        }
        var row = new CollectionImportEntity();
        init(row, auth);
        row.setId(task.getRecordId());
        row.setProjectId(task.getProjectId());
        row.setKind(task.getKind());
        row.setFileName(task.getFileName());
        row.setStatus("PROCESSING");
        row.setPhase("QUEUED");
        row.setProcessedCount(0);
        row.setTotalCount(0);
        row.setSuccessCount(0);
        row.setRejectedCount(0);
        row.setErrorCount(0);
        row.setOperatorId(auth.getUser().getId());
        row.setOperatorName(FinanceScope.operatorName(auth));
        return collections.insert(row).then();
    }

    /** 所有批次先锁定并验证版本，再一起入队；后台按批次处理，失败保留已完成行和剩余待审行。 */
    public Mono<List<CollectionImportEntity>> reviewCollections(
            String project,
            String kind,
            CollectionImportService.BulkDecision input,
            boolean approve) {
        if (input == null || input.records() == null)
            return Mono.error(error("collection_review_invalid", 400));
        var items =
                input.records().stream()
                        .map(i -> i == null ? null : new ReviewItem(i.id(), i.version()))
                        .toList();
        return enqueueReviews(project, kind, items, input.reason(), approve)
                .thenMany(Flux.fromIterable(items).concatMap(i -> collections.findById(i.id()), 1))
                .collectList();
    }

    public Mono<List<ReceivableImportEntity>> reviewHistorical(
            String project, HistoricalImportAuditService.BatchDecision input, boolean approve) {
        if (input == null || input.records() == null)
            return Mono.error(error("historical_review_invalid", 400));
        var items =
                input.records().stream()
                        .map(i -> i == null ? null : new ReviewItem(i.id(), i.version()))
                        .toList();
        return enqueueReviews(project, "HISTORICAL", items, input.reason(), approve)
                .thenMany(Flux.fromIterable(items).concatMap(i -> historical.findById(i.id()), 1))
                .collectList();
    }

    private record ReviewItem(String id, Long version) {}

    private Mono<Void> enqueueReviews(
            String project, String kind, List<ReviewItem> items, String reason, boolean approve) {
        if (items.isEmpty()
                || items.size() > 20
                || items.stream().anyMatch(i -> i == null || i.id() == null || i.version() == null)
                || items.stream().map(ReviewItem::id).distinct().count() != items.size()
                || reason == null
                || reason.isBlank()
                || reason.length() > 1000)
            return Mono.error(error("collection_review_invalid", 400));
        if (!kind.equals("HISTORICAL") && !CollectionImportWorkbook.KINDS.contains(kind))
            return Mono.error(error("collection_kind_invalid", 400));
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                FinanceJobAuthority.capture(auth, "collection_authority_invalid")
                                        .flatMap(
                                                snapshot ->
                                                        TenantHolder.currentId()
                                                                .defaultIfEmpty("")
                                                                .flatMap(
                                                                        tenant ->
                                                                                TransactionalOperator
                                                                                        .create(
                                                                                                transactions)
                                                                                        .transactional(
                                                                                                Flux
                                                                                                        .fromIterable(
                                                                                                                items
                                                                                                                        .stream()
                                                                                                                        .sorted(
                                                                                                                                Comparator
                                                                                                                                        .comparing(
                                                                                                                                                ReviewItem
                                                                                                                                                        ::id))
                                                                                                                        .toList())
                                                                                                        .concatMap(
                                                                                                                item -> {
                                                                                                                    var
                                                                                                                            task =
                                                                                                                                    new FinanceImportTaskEntity();
                                                                                                                    init(
                                                                                                                            task,
                                                                                                                            auth);
                                                                                                                    task
                                                                                                                            .setId(
                                                                                                                                    UUID.randomUUID()
                                                                                                                                            .toString());
                                                                                                                    task
                                                                                                                            .setRecordId(
                                                                                                                                    item
                                                                                                                                            .id());
                                                                                                                    task
                                                                                                                            .setProjectId(
                                                                                                                                    project);
                                                                                                                    task
                                                                                                                            .setKind(
                                                                                                                                    kind);
                                                                                                                    task
                                                                                                                            .setOperation(
                                                                                                                                    approve
                                                                                                                                            ? "APPROVE"
                                                                                                                                            : "REJECT");
                                                                                                                    task
                                                                                                                            .setState(
                                                                                                                                    "QUEUED");
                                                                                                                    task
                                                                                                                            .setLeaseUntil(
                                                                                                                                    0L);
                                                                                                                    task
                                                                                                                            .setReason(
                                                                                                                                    reason
                                                                                                                                            .trim());
                                                                                                                    task
                                                                                                                            .setTenantId(
                                                                                                                                    tenant);
                                                                                                                    task
                                                                                                                            .setExecutorSnapshot(
                                                                                                                                    snapshot);
                                                                                                                    task
                                                                                                                            .setSourceKey(
                                                                                                                                    DigestUtils
                                                                                                                                            .sha256Hex(
                                                                                                                                                    "REVIEW:"
                                                                                                                                                            + kind
                                                                                                                                                            + ":"
                                                                                                                                                            + item
                                                                                                                                                                    .id()
                                                                                                                                                            + ":"
                                                                                                                                                            + item
                                                                                                                                                                    .version()));
                                                                                                                    Mono<
                                                                                                                                    Integer>
                                                                                                                            changed =
                                                                                                                                    kind
                                                                                                                                                    .equals(
                                                                                                                                                            "HISTORICAL")
                                                                                                                                            ? historical
                                                                                                                                                    .createUpdate()
                                                                                                                                                    .set(
                                                                                                                                                            "status",
                                                                                                                                                            "REVIEWING")
                                                                                                                                                    .set(
                                                                                                                                                            "phase",
                                                                                                                                                            "QUEUED")
                                                                                                                                                    .set(
                                                                                                                                                            "processedCount",
                                                                                                                                                            0)
                                                                                                                                                    .set(
                                                                                                                                                            "errorCount",
                                                                                                                                                            0)
                                                                                                                                                    .set(
                                                                                                                                                            "errorMessage",
                                                                                                                                                            "")
                                                                                                                                                    .set(
                                                                                                                                                            "version",
                                                                                                                                                            item
                                                                                                                                                                            .version()
                                                                                                                                                                    + 1)
                                                                                                                                                    .where(
                                                                                                                                                            "id",
                                                                                                                                                            item
                                                                                                                                                                    .id())
                                                                                                                                                    .and(
                                                                                                                                                            "projectId",
                                                                                                                                                            project)
                                                                                                                                                    .and(
                                                                                                                                                            "status",
                                                                                                                                                            "PENDING")
                                                                                                                                                    .and(
                                                                                                                                                            "version",
                                                                                                                                                            item
                                                                                                                                                                    .version())
                                                                                                                                                    .and(
                                                                                                                                                            "deleted",
                                                                                                                                                            false)
                                                                                                                                                    .execute()
                                                                                                                                            : collections
                                                                                                                                                    .createUpdate()
                                                                                                                                                    .set(
                                                                                                                                                            "status",
                                                                                                                                                            "REVIEWING")
                                                                                                                                                    .set(
                                                                                                                                                            "phase",
                                                                                                                                                            "QUEUED")
                                                                                                                                                    .set(
                                                                                                                                                            "processedCount",
                                                                                                                                                            0)
                                                                                                                                                    .set(
                                                                                                                                                            "errorCount",
                                                                                                                                                            0)
                                                                                                                                                    .set(
                                                                                                                                                            "errorMessage",
                                                                                                                                                            "")
                                                                                                                                                    .set(
                                                                                                                                                            "version",
                                                                                                                                                            item
                                                                                                                                                                            .version()
                                                                                                                                                                    + 1)
                                                                                                                                                    .where(
                                                                                                                                                            "id",
                                                                                                                                                            item
                                                                                                                                                                    .id())
                                                                                                                                                    .and(
                                                                                                                                                            "projectId",
                                                                                                                                                            project)
                                                                                                                                                    .and(
                                                                                                                                                            "kind",
                                                                                                                                                            kind)
                                                                                                                                                    .and(
                                                                                                                                                            "status",
                                                                                                                                                            "PENDING")
                                                                                                                                                    .and(
                                                                                                                                                            "version",
                                                                                                                                                            item
                                                                                                                                                                    .version())
                                                                                                                                                    .and(
                                                                                                                                                            "deleted",
                                                                                                                                                            false)
                                                                                                                                                    .execute();
                                                                                                                    return changed
                                                                                                                            .flatMap(
                                                                                                                                    n ->
                                                                                                                                            n
                                                                                                                                                            == 1
                                                                                                                                                    ? tasks.insert(
                                                                                                                                                                    task)
                                                                                                                                                            .then()
                                                                                                                                                    : Mono
                                                                                                                                                            .error(
                                                                                                                                                                    error(
                                                                                                                                                                            "version_conflict",
                                                                                                                                                                            409)));
                                                                                                                },
                                                                                                                1)
                                                                                                        .then()))));
    }

    public Mono<Void> retry(String project, String kind, String record) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                tasks.createQuery()
                                        .where("recordId", record)
                                        .and("projectId", project)
                                        .and("kind", kind)
                                        .and("operation", "UPLOAD")
                                        .and("state", "FAILED")
                                        .fetchOne()
                                        .switchIfEmpty(
                                                Mono.error(error("collection_retry_invalid", 409)))
                                        .flatMap(
                                                task ->
                                                        Mono.fromCallable(
                                                                        () ->
                                                                                Files.exists(
                                                                                        input(
                                                                                                task)))
                                                                .subscribeOn(
                                                                        Schedulers.boundedElastic())
                                                                .flatMap(
                                                                        exists -> {
                                                                            if (!exists)
                                                                                return Mono.error(
                                                                                        error(
                                                                                                "collection_retry_missing",
                                                                                                409));
                                                                            return FinanceJobAuthority
                                                                                    .capture(
                                                                                            auth,
                                                                                            "collection_authority_invalid")
                                                                                    .flatMap(
                                                                                            snapshot ->
                                                                                                    TenantHolder
                                                                                                            .currentId()
                                                                                                            .defaultIfEmpty(
                                                                                                                    "")
                                                                                                            .flatMap(
                                                                                                                    tenant -> {
                                                                                                                        if (!Objects
                                                                                                                                .equals(
                                                                                                                                        tenant,
                                                                                                                                        task
                                                                                                                                                .getTenantId()))
                                                                                                                            return Mono
                                                                                                                                    .error(
                                                                                                                                            error(
                                                                                                                                                    "collection_authority_invalid",
                                                                                                                                                    403));
                                                                                                                        return TransactionalOperator
                                                                                                                                .create(
                                                                                                                                        transactions)
                                                                                                                                .transactional(
                                                                                                                                        tasks.createUpdate()
                                                                                                                                                .set(
                                                                                                                                                        "state",
                                                                                                                                                        "QUEUED")
                                                                                                                                                .set(
                                                                                                                                                        "executorSnapshot",
                                                                                                                                                        snapshot)
                                                                                                                                                .set(
                                                                                                                                                        "leaseUntil",
                                                                                                                                                        0L)
                                                                                                                                                .where(
                                                                                                                                                        "id",
                                                                                                                                                        task
                                                                                                                                                                .getId())
                                                                                                                                                .and(
                                                                                                                                                        "state",
                                                                                                                                                        "FAILED")
                                                                                                                                                .execute()
                                                                                                                                                .flatMap(
                                                                                                                                                        n -> {
                                                                                                                                                            if (n
                                                                                                                                                                    != 1)
                                                                                                                                                                return Mono
                                                                                                                                                                        .error(
                                                                                                                                                                                error(
                                                                                                                                                                                        "version_conflict",
                                                                                                                                                                                        409));
                                                                                                                                                            return kind
                                                                                                                                                                            .equals(
                                                                                                                                                                                    "HISTORICAL")
                                                                                                                                                                    ? historical
                                                                                                                                                                            .createUpdate()
                                                                                                                                                                            .set(
                                                                                                                                                                                    "status",
                                                                                                                                                                                    "PROCESSING")
                                                                                                                                                                            .set(
                                                                                                                                                                                    "phase",
                                                                                                                                                                                    "QUEUED")
                                                                                                                                                                            .set(
                                                                                                                                                                                    "errorCount",
                                                                                                                                                                                    0)
                                                                                                                                                                            .set(
                                                                                                                                                                                    "errorMessage",
                                                                                                                                                                                    "")
                                                                                                                                                                            .where(
                                                                                                                                                                                    "id",
                                                                                                                                                                                    record)
                                                                                                                                                                            .and(
                                                                                                                                                                                    "status",
                                                                                                                                                                                    "FAILED")
                                                                                                                                                                            .execute()
                                                                                                                                                                    : collections
                                                                                                                                                                            .createUpdate()
                                                                                                                                                                            .set(
                                                                                                                                                                                    "status",
                                                                                                                                                                                    "PROCESSING")
                                                                                                                                                                            .set(
                                                                                                                                                                                    "phase",
                                                                                                                                                                                    "QUEUED")
                                                                                                                                                                            .set(
                                                                                                                                                                                    "errorCount",
                                                                                                                                                                                    0)
                                                                                                                                                                            .set(
                                                                                                                                                                                    "errorMessage",
                                                                                                                                                                                    "")
                                                                                                                                                                            .where(
                                                                                                                                                                                    "id",
                                                                                                                                                                                    record)
                                                                                                                                                                            .and(
                                                                                                                                                                                    "status",
                                                                                                                                                                                    "FAILED")
                                                                                                                                                                            .execute();
                                                                                                                                                        })
                                                                                                                                                .flatMap(
                                                                                                                                                        n ->
                                                                                                                                                                n
                                                                                                                                                                                == 1
                                                                                                                                                                        ? Mono
                                                                                                                                                                                .<Void>
                                                                                                                                                                                        empty()
                                                                                                                                                                        : Mono
                                                                                                                                                                                .error(
                                                                                                                                                                                        error(
                                                                                                                                                                                                "version_conflict",
                                                                                                                                                                                                409))));
                                                                                                                    }));
                                                                        })));
    }

    /** 合并心跳和业务订阅；业务结束取消心跳，租约丢失则取消当前短事务。 */
    private Mono<Void> withHeartbeat(FinanceImportTaskEntity task) {
        return Flux.merge(
                        run(task).thenReturn(true),
                        Flux.interval(Duration.ofSeconds(30))
                                .concatMap(tick -> renew(task).thenReturn(false), 1))
                .takeUntil(Boolean::booleanValue)
                .then();
    }

    private Mono<Void> renew(FinanceImportTaskEntity task) {
        return tasks.createUpdate()
                .set("leaseUntil", System.currentTimeMillis() + 300_000)
                .where("id", task.getId())
                .and("leaseOwner", task.getLeaseOwner())
                .and("state", "RUNNING")
                .execute()
                .flatMap(n -> n == 1 ? Mono.empty() : Mono.error(error("version_conflict", 409)));
    }

    private void registerMBean() {
        try {
            objectName =
                    new javax.management.ObjectName(
                            "org.jetlinks.property.finance:type=FinanceImportQueue");
            var server = java.lang.management.ManagementFactory.getPlatformMBeanServer();
            if (!server.isRegistered(objectName)) server.registerMBean(this, objectName);
        } catch (Exception failure) {
            log.warn("Unable to register import queue MBean", failure);
        }
    }

    private void unregisterMBean() {
        try {
            if (objectName != null) {
                var server = java.lang.management.ManagementFactory.getPlatformMBeanServer();
                if (server.isRegistered(objectName)) server.unregisterMBean(objectName);
                objectName = null;
            }
        } catch (Exception failure) {
            log.warn("Unable to unregister import queue MBean", failure);
        }
    }

    Mono<Void> poll() {
        var q = new QueryParamEntity();
        q.setPaging(true);
        q.setPageSize(1);
        q.toQuery().orderByAsc("createTime");
        return expireFiles()
                .then(
                        tasks.createQuery()
                                .setParam(q)
                                .in("state", List.of("QUEUED", "RUNNING"))
                                .lte("leaseUntil", System.currentTimeMillis())
                                .fetchOne()
                                .flatMap(this::claimAndRun)
                                .then())
                .contextWrite(ignored -> Context.empty());
    }

    Mono<Void> claimAndRun(FinanceImportTaskEntity task) {
        var owner = UUID.randomUUID().toString();
        return tasks.createUpdate()
                .set("state", "RUNNING")
                .set("leaseOwner", owner)
                .set("leaseUntil", System.currentTimeMillis() + 300_000)
                .where("id", task.getId())
                .lte("leaseUntil", System.currentTimeMillis())
                .in("state", List.of("QUEUED", "RUNNING"))
                .execute()
                .filter(n -> n == 1)
                .flatMap(
                        n -> {
                            task.setLeaseOwner(owner);
                            var tenant =
                                    task.getTenantId() == null || task.getTenantId().isBlank()
                                            ? Context.empty()
                                            : TenantHolder.makeCurrent(task.getTenantId());
                            return authentications
                                    .apply(
                                            FinanceJobAuthority.executorId(
                                                    task.getExecutorSnapshot(),
                                                    "collection_authority_invalid"))
                                    .switchIfEmpty(
                                            Mono.error(error("collection_authority_invalid", 403)))
                                    .flatMap(
                                            auth ->
                                                    FinanceJobAuthority.restore(
                                                            task.getExecutorSnapshot(),
                                                            task.getTenantId(),
                                                            auth,
                                                            a ->
                                                                    a.hasPermission(
                                                                            task.getKind()
                                                                                            .equals(
                                                                                                    "HISTORICAL")
                                                                                    ? "property-finance-receivable"
                                                                                    : "property-finance-collection-import",
                                                                            task.getOperation()
                                                                                            .equals(
                                                                                                    "UPLOAD")
                                                                                    ? "import"
                                                                                    : task.getOperation()
                                                                                                    .equals(
                                                                                                            "APPROVE")
                                                                                            ? "approve"
                                                                                            : "reject"),
                                                            "collection_authority_invalid"))
                                    .flatMap(
                                            context ->
                                                    withHeartbeat(task)
                                                            .contextWrite(ignored -> context))
                                    .contextWrite(ignored -> tenant)
                                    .then(
                                            tasks.createUpdate()
                                                    .set("state", "DONE")
                                                    .set("executorSnapshot", "")
                                                    .set("leaseUntil", 0L)
                                                    .where("id", task.getId())
                                                    .and("leaseOwner", owner)
                                                    .execute())
                                    .flatMap(
                                            changed -> {
                                                if (changed != 1)
                                                    return Mono.error(
                                                            error("version_conflict", 409));
                                                successes.incrementAndGet();
                                                return deleteFiles(task);
                                            })
                                    .onErrorResume(failure -> fail(task, failure));
                        })
                .then();
    }

    private Mono<Void> run(FinanceImportTaskEntity task) {
        if (!task.getOperation().equals("UPLOAD"))
            return task.getKind().equals("HISTORICAL")
                    ? historicalAudit
                            .getObject()
                            .processLarge(
                                    task.getProjectId(),
                                    task.getRecordId(),
                                    task.getReason(),
                                    task.getOperation().equals("APPROVE"),
                                    n -> progress(task, "REVIEWING", n))
                    : collectionService
                            .getObject()
                            .reviewLarge(
                                    task.getProjectId(),
                                    task.getKind(),
                                    task.getRecordId(),
                                    task.getReason(),
                                    task.getOperation().equals("APPROVE"),
                                    n -> progress(task, "REVIEWING", n));
        var raw = root.resolve(task.getId() + ".raw.jsonl");
        var prepared = root.resolve(task.getId() + ".prepared.jsonl");
        return progress(task, "VALIDATING", 0)
                .then(
                        Mono.fromCallable(
                                        () -> {
                                            FinanceExcelRows.spool(input(task), raw);
                                            return raw;
                                        })
                                .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(
                        path ->
                                task.getKind().equals("HISTORICAL")
                                        ? historicalService
                                                .getObject()
                                                .processLarge(
                                                        task.getProjectId(),
                                                        task.getRecordId(),
                                                        path,
                                                        prepared,
                                                        (phase, n) -> progress(task, phase, n))
                                        : collectionService
                                                .getObject()
                                                .processLarge(
                                                        task.getProjectId(),
                                                        task.getKind(),
                                                        task.getRecordId(),
                                                        path,
                                                        prepared,
                                                        (phase, n) -> progress(task, phase, n)))
                .as(MonoTracer.create("property.finance.import.background"));
    }

    private Mono<Void> progress(FinanceImportTaskEntity task, String phase, int count) {
        Mono<Integer> update =
                task.getKind().equals("HISTORICAL")
                        ? historical
                                .createUpdate()
                                .set("phase", phase)
                                .set("processedCount", count)
                                .where("id", task.getRecordId())
                                .execute()
                        : collections
                                .createUpdate()
                                .set("phase", phase)
                                .set("processedCount", count)
                                .where("id", task.getRecordId())
                                .execute();
        return tasks.createUpdate()
                .set("leaseUntil", System.currentTimeMillis() + 300_000)
                .where("id", task.getId())
                .and("leaseOwner", task.getLeaseOwner())
                .and("state", "RUNNING")
                .execute()
                .flatMap(n -> n == 1 ? update : Mono.error(error("version_conflict", 409)))
                .then();
    }

    private Mono<Void> fail(FinanceImportTaskEntity task, Throwable failure) {
        failures.incrementAndGet();
        log.warn("Finance import task {} failed", task.getId(), failure);
        String message =
                failure instanceof BusinessException e
                        ? org.hswebframework.web.i18n.LocaleUtils.resolveMessage(
                                e.getI18nCode(), e.getMessage(), e.getArgs())
                        : org.hswebframework.web.i18n.LocaleUtils.resolveMessage(
                                "error.property_finance_collection_failed", "导入处理失败，请查看导入记录并重试");
        message = message.substring(0, Math.min(8000, message.length()));
        Mono<Integer> update =
                task.getKind().equals("HISTORICAL")
                        ? historical
                                .createUpdate()
                                .set(
                                        "status",
                                        task.getOperation().equals("UPLOAD") ? "FAILED" : "PENDING")
                                .set("phase", "FAILED")
                                .set("errorCount", 1)
                                .set("errorMessage", message)
                                .where("id", task.getRecordId())
                                .execute()
                        : collections
                                .createUpdate()
                                .set(
                                        "status",
                                        task.getOperation().equals("UPLOAD") ? "FAILED" : "PENDING")
                                .set("phase", "FAILED")
                                .set("errorCount", 1)
                                .set("errorMessage", message)
                                .where("id", task.getRecordId())
                                .execute();
        return tasks.createUpdate()
                .set("state", "FAILED")
                .set("errorMessage", message)
                .set("leaseUntil", 0L)
                .where("id", task.getId())
                .and("leaseOwner", task.getLeaseOwner())
                .and("state", "RUNNING")
                .execute()
                .flatMap(n -> n == 1 ? update : Mono.empty())
                .then();
    }

    private Mono<Void> expireFiles() {
        var q = new QueryParamEntity();
        q.setPaging(true);
        q.setPageSize(5);
        return tasks.createQuery()
                .setParam(q)
                .where("state", "FAILED")
                .lt("createTime", System.currentTimeMillis() - Duration.ofDays(7).toMillis())
                .fetch()
                .concatMap(
                        task ->
                                Mono.fromCallable(
                                                () ->
                                                        Files.exists(
                                                                root.resolve(
                                                                        task.getId()
                                                                                + ".prepared.jsonl")))
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .flatMap(
                                                checkpoint ->
                                                        checkpoint
                                                                ? Mono.empty()
                                                                : deleteFiles(task)
                                                                        .then(
                                                                                tasks.createUpdate()
                                                                                        .set(
                                                                                                "state",
                                                                                                "EXPIRED")
                                                                                        .set(
                                                                                                "sourceKey",
                                                                                                "expired:"
                                                                                                        + task
                                                                                                                .getId())
                                                                                        .set(
                                                                                                "executorSnapshot",
                                                                                                "")
                                                                                        .where(
                                                                                                "id",
                                                                                                task
                                                                                                        .getId())
                                                                                        .and(
                                                                                                "state",
                                                                                                "FAILED")
                                                                                        .execute())),
                        1)
                .then();
    }

    private Path input(FinanceImportTaskEntity task) {
        return root.resolve(
                task.getId()
                        + (task.getFileName().toLowerCase(Locale.ROOT).endsWith(".xls")
                                ? ".xls"
                                : ".xlsx"));
    }

    private Mono<Void> deleteFiles(FinanceImportTaskEntity task) {
        return Mono.fromRunnable(
                        () -> {
                            try {
                                if (task.getFileName() != null) Files.deleteIfExists(input(task));
                                Files.deleteIfExists(root.resolve(task.getId() + ".raw.jsonl"));
                                Files.deleteIfExists(
                                        root.resolve(task.getId() + ".prepared.jsonl"));
                            } catch (Exception e) {
                                log.warn("Unable to clean import task {} files", task.getId());
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Override
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (running || !autoStart) return;
        registerMBean();
        running = true;
        subscription =
                Flux.interval(Duration.ofSeconds(3))
                        .onBackpressureDrop()
                        .concatMap(
                                tick ->
                                        poll().onErrorResume(
                                                        e -> {
                                                            log.warn(
                                                                    "Finance import scanner failed",
                                                                    e);
                                                            return Mono.empty();
                                                        }),
                                0)
                        .subscribe();
    }

    @Override
    public boolean isAutoStartup() {
        return false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public synchronized void stop() {
        running = false;
        unregisterMBean();
        if (subscription != null) {
            subscription.dispose();
            subscription = null;
        }
    }
}
