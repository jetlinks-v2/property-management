package org.jetlinks.pro.smartpark.handover.export;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.exception.NotFoundException;
import org.hswebframework.web.id.IDGenerator;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.io.file.FileManager;
import org.jetlinks.pro.smartpark.handover.api.*;
import org.jetlinks.pro.smartpark.handover.entity.*;
import org.jetlinks.pro.smartpark.handover.service.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.io.buffer.*;
import org.springframework.stereotype.Service;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Durable queue: every tick re-reads DB; conditional leases coordinate all service instances. */
@Service
@RequiredArgsConstructor
@Slf4j
public class HandoverExportService implements SmartLifecycle {
    static final Duration LEASE = Duration.ofMinutes(2), HEARTBEAT = Duration.ofSeconds(20);
    private static final Duration POLL_DELAY = Duration.ofSeconds(5);
    private final HandoverExportRepository tasks;
    private final HandoverScope scope;
    private final HandoverExportScope authorization;
    private final HandoverExportRows rows;
    private final FileManager files;
    private final String owner = UUID.randomUUID().toString();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicLong completed = new AtomicLong(),
            failed = new AtomicLong(),
            claimed = new AtomicLong();
    private volatile String lastError = "";
    private volatile Disposable worker;

    static <T> Mono<T> io(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    static Mono<Void> runIo(IoAction action) {
        return io(() -> {
                    action.run();
                    return true;
                })
                .then();
    }

    @FunctionalInterface
    interface IoAction {
        void run() throws Exception;
    }

    static HandoverContext creator(HandoverExportEntity task) {
        return new HandoverContext(
                task.getTenantId(),
                task.getApplicationId(),
                task.getCreatorId(),
                task.getCreatorName());
    }

    public Mono<HandoverExportEntity> create(ExportRequest request) {
        rows.validate(request);
        return scope.current()
                .flatMap(
                        context ->
                                authorization.execute(
                                        context,
                                        () ->
                                                rows.selector(request, 0, null)
                                                        .then(
                                                                Mono.defer(
                                                                        () -> {
                                                                            HandoverExportEntity
                                                                                    task =
                                                                                            new HandoverExportEntity();
                                                                            scope.initialize(
                                                                                    task, context);
                                                                            task.setId(
                                                                                    IDGenerator.MD5
                                                                                            .generate());
                                                                            task.setProjectId(
                                                                                    Objects.toString(request.getProjectId(), "")); // 空字符串仅表示跨项目台账任务，不是资产授权值。
                                                                            task.setKind(
                                                                                    request
                                                                                            .getKind());
                                                                            task.setRequest(
                                                                                    request);
                                                                            task.setStatus(
                                                                                    "pending");
                                                                            task.setAttempts(0);
                                                                            task.setLeaseUntil(0L);
                                                                            task.setFileName(
                                                                                    "承接查验-"
                                                                                            + request
                                                                                                    .getKind()
                                                                                            + "-"
                                                                                            + task
                                                                                                    .getId()
                                                                                            + ".xlsx");
                                                                            // Persist before
                                                                            // returning:
                                                                            // scheduling, restarts
                                                                            // and client
                                                                            // disconnects cannot
                                                                            // lose the job.
                                                                            return tasks.insert(
                                                                                            task)
                                                                                    .thenReturn(
                                                                                            task);
                                                                        }))));
    }

    public Mono<PagerResult<HandoverExportEntity>> query(HandoverQuery input) {
        if ("ledger".equals(input.getKind()) && !HandoverRules.hasText(input.getProjectId())) {
            return scope.current().flatMap(c -> authorization.execute(c, () -> {
                var query = taskQuery(c, null, input.getPageIndex(), input.getPageSize());
                query.toQuery().and("kind", "ledger");
                return tasks.queryPager(query);
            }));
        }
        return scope.current()
                .flatMap(
                        c ->
                                authorization.execute(
                                        c,
                                        () ->
                                                scope.project(
                                                                input.getProjectId(),
                                                                CrudAssetPermission.read)
                                                        .then(
                                                                tasks.queryPager(
                                                                        taskQuery(
                                                                                c,
                                                                                input
                                                                                        .getProjectId(),
                                                                                input
                                                                                        .getPageIndex(),
                                                                                input
                                                                                        .getPageSize())))));
    }

    private QueryParamEntity taskQuery(HandoverContext c, String project, int page, int size) {
        QueryParamEntity q = scope.mandatory(new QueryParamEntity(), c, project);
        q.toQuery().and("creatorId", c.userId()).orderByDesc("createTime");
        q.setPageIndex(Math.max(0, page));
        q.setPageSize(Math.max(1, Math.min(100, size)));
        return q;
    }

    public Mono<HandoverExportEntity> visible(String id) {
        return scope.current()
                .flatMap(
                        c ->
                                tasks.findById(id)
                                        .switchIfEmpty(Mono.error(new NotFoundException()))
                                        .doOnNext(
                                                task -> {
                                                    scope.owned(task, c);
                                                    if (!Objects.equals(
                                                            task.getCreatorId(), c.userId()))
                                                        throw new AccessDenyException();
                                                })
                                        .flatMap(
                                                task ->
                                                        authorization.execute(
                                                                c,
                                                                () -> assertTaskScope(task).thenReturn(task))));
    }

    public Mono<HandoverExportEntity> retry(String id) {
        return visible(id)
                .flatMap(
                        task -> {
                            HandoverRules.require(
                                    "failed".equals(task.getStatus()), "export_retry_state");
                            return tasks.createUpdate()
                                    .set("status", "pending")
                                    .set("error", null)
                                    .set("finishTime", null)
                                    .set("leaseUntil", 0L)
                                    .set("leaseOwner", null)
                                    .set("version", task.getVersion() + 1)
                                    .where("id", id)
                                    .and("status", "failed")
                                    .and("version", task.getVersion())
                                    .execute()
                                    .doOnNext(n -> HandoverRules.conflict(n == 1))
                                    .then(tasks.findById(id));
                        });
    }

    @Override
    public synchronized void start() {
        if (isRunning()) return;
        // 组件拥有唯一消费循环；共享 Spring 调度线程无需等待导出或租约续期。
        worker = Mono.delay(POLL_DELAY)
                .then(Mono.defer(this::tick).onErrorResume(error -> Mono.empty()))
                .repeat()
                .subscribe();
    }

    @Override
    public synchronized void stop() {
        if (worker != null) worker.dispose();
        worker = null;
    }

    @Override
    public boolean isRunning() {
        var current = worker;
        return current != null && !current.isDisposed();
    }

    Mono<Void> tick() {
        // 先消费完有限的候选结果，释放查询连接，再串行领取和执行长任务。
        return Mono.defer(() -> candidates().collectList())
                .timeout(Duration.ofSeconds(10))
                .flatMapMany(Flux::fromIterable)
                .concatMap(this::claimAndRun, 1)
                .then()
                .doOnError(
                        error -> {
                            lastError = message(error);
                            log.error("Handover export queue tick failed", error);
                        });
    }

    private Flux<HandoverExportEntity> candidates() {
        QueryParamEntity pending =
                QueryParamEntity.newQuery()
                        .where("status", "pending")
                        .and("deleted", false)
                        .orderByAsc("createTime")
                        .doPaging(0, 4)
                        .getParam();
        QueryParamEntity expired =
                QueryParamEntity.newQuery()
                        .where("status", "running")
                        .and("leaseUntil", "lt", System.currentTimeMillis())
                        .and("deleted", false)
                        .orderByAsc("leaseUntil")
                        .doPaging(0, 4)
                        .getParam();
        return tasks.createQuery().setParam(expired).fetch()
                .concatWith(Flux.defer(() -> tasks.createQuery().setParam(pending).fetch()));
    }

    Mono<Boolean> claim(HandoverExportEntity task) {
        long now = System.currentTimeMillis();
        var update =
                tasks.createUpdate()
                        .set("status", "running")
                        .set("leaseOwner", owner)
                        .set("leaseUntil", now + LEASE.toMillis())
                        .set("attempts", Objects.requireNonNullElse(task.getAttempts(), 0) + 1)
                        .set("version", task.getVersion() + 1)
                        .set("error", null)
                        .where("id", task.getId())
                        .and("status", task.getStatus())
                        .and("version", task.getVersion())
                        .and("deleted", false);
        if ("running".equals(task.getStatus())) update.and("leaseUntil", "lt", now);
        return update.execute()
                .map(
                        n -> {
                            if (n != 1) return false;
                            task.setStatus("running");
                            task.setLeaseOwner(owner);
                            task.setVersion(task.getVersion() + 1);
                            claimed.incrementAndGet();
                            return true;
                        });
    }

    private Mono<Void> claimAndRun(HandoverExportEntity task) {
        return claim(task).flatMap(won -> won ? execute(task) : Mono.empty());
    }

    private Mono<Void> execute(HandoverExportEntity task) {
        Mono<Void> work =
                Mono.defer(
                                () -> {
                                    active.incrementAndGet();
                                    return generate(task);
                                })
                        .timeout(Duration.ofHours(24));
        // Heartbeat failure cancels generation. A stale owner can neither finish nor overwrite a
        // retry.
        Mono<Void> heartbeat =
                Flux.interval(HEARTBEAT)
                        .concatMap(ignore -> renew(task).timeout(Duration.ofSeconds(10)), 1)
                        .then();
        return Mono.firstWithSignal(work, heartbeat)
                .doFinally(signal -> active.decrementAndGet())
                .onErrorResume(error -> markFailed(task, error))
                .as(
                        MonoTracer.create(
                                "/property/handover/export/execute",
                                span -> span.setAttribute("taskId", task.getId())));
    }

    private Mono<Void> renew(HandoverExportEntity task) {
        return tasks.createUpdate()
                .set("leaseUntil", System.currentTimeMillis() + LEASE.toMillis())
                .where("id", task.getId())
                .and("status", "running")
                .and("leaseOwner", owner)
                .and("version", task.getVersion())
                .execute()
                .flatMap(
                        n ->
                                n == 1
                                        ? Mono.empty()
                                        : tasks.findById(task.getId())
                                                // Completion may commit just before a heartbeat
                                                // returns; never cancel its file cleanup.
                                                .filter(
                                                        current ->
                                                                "completed"
                                                                                .equals(
                                                                                        current
                                                                                                .getStatus())
                                                                        && owner.equals(
                                                                                current
                                                                                        .getLeaseOwner())
                                                                        && Objects.equals(
                                                                                task.getVersion(),
                                                                                current
                                                                                        .getVersion()))
                                                .switchIfEmpty(
                                                        Mono.error(
                                                                new IllegalStateException(
                                                                        "Export lease was lost")))
                                                .then());
    }

    private Mono<Void> markFailed(HandoverExportEntity task, Throwable error) {
        failed.incrementAndGet();
        lastError = message(error);
        log.error("Handover export {} failed", task.getId(), error);
        return tasks.createUpdate()
                .set("status", "failed")
                .set("error", lastError)
                .set("finishTime", System.currentTimeMillis())
                .set("leaseUntil", 0L)
                .where("id", task.getId())
                .and("status", "running")
                .and("leaseOwner", owner)
                .and("version", task.getVersion())
                .execute()
                .doOnNext(
                        n -> {
                            if (n != 1)
                                log.warn(
                                        "Cannot mark stale export {} failed; lease changed",
                                        task.getId());
                        })
                .then();
    }

    static String message(Throwable error) {
        String text =
                error.getClass().getSimpleName()
                        + ": "
                        + Objects.toString(error.getMessage(), "export failed");
        return text.substring(0, Math.min(1900, text.length()));
    }

    private Mono<Void> generate(HandoverExportEntity task) {
        var rendering = authorization.session(creator(task));
        return Mono.usingWhen(
                io(() -> new HandoverExportWorkbook(task.getKind())),
                book ->
                        writeRows(task, book, rendering)
                                .then(runIo(book::finish))
                                .then(authorization.execute(creator(task), () -> save(task, book))),
                book -> runIo(book::close),
                (book, error) -> runIo(book::close),
                book -> runIo(book::close));
    }

    private Mono<Void> writeRows(
            HandoverExportEntity task, HandoverExportWorkbook book,
            HandoverExportScope.Session session) {
        // 按页授权并顺序写入；当前页处理完才查询下一页。
        return Flux.range(0, Integer.MAX_VALUE)
                .concatMap(
                        page -> session.execute(() -> rows.page(task.getRequest(), page))
                                .flatMap(batch -> Flux.fromIterable(batch)
                                        .concatMap(row -> writeRow(book, row, session), 1)
                                        .then(Mono.just(batch.size()))),
                        1)
                .takeUntil(size -> size < HandoverExportRows.PAGE_SIZE)
                .then();
    }

    private Mono<Void> writeRow(
            HandoverExportWorkbook book, HandoverEntity row,
            HandoverExportScope.Session session) {
        Mono<Void> content =
                session.execute(() -> rows.values(row))
                        .flatMap(values -> runIo(() -> book.row(row.getId(), values)));
        if (row instanceof HandoverProblemEntity) {
            content =
                    content.thenMany(
                                    rows.photos(row)
                                            .concatMap(
                                                    photo -> session.execute(() -> photo(
                                                            book, photo.getFileId(), photo.getPhotoType())),
                                                    1))
                            .then()
                            .thenMany(
                                    rows.nodes(row)
                                            .concatMap(
                                                    node ->
                                                            runIo(
                                                                    () ->
                                                                            book.node(
                                                                                    node.getTime()
                                                                                            + " "
                                                                                            + node
                                                                                                    .getAction()
                                                                                            + " "
                                                                                            + node
                                                                                                    .getOperatorName()
                                                                                            + " "
                                                                                            + Objects
                                                                                                    .toString(
                                                                                                            node
                                                                                                                    .getDescription(),
                                                                                                            ""))),
                                                    1))
                            .then();
        } else {
            List<FileRef> refs =
                    row instanceof HandoverLedgerEntity ledger
                            ? ledger.getFiles()
                            : row instanceof HandoverRecordEntity record
                                    ? record.getFiles()
                                    : List.of();
            content =
                    content.thenMany(
                                    Flux.fromIterable(refs == null ? List.<FileRef>of() : refs)
                                            .sort(
                                                    Comparator.comparing(
                                                            ref ->
                                                                    Objects.requireNonNullElse(
                                                                            ref.getSortIndex(), 0)))
                                            .concatMap(
                                                    ref -> session.execute(() -> attachment(book, ref)),
                                                    1))
                            .then();
        }
        return content;
    }

    private Mono<Void> attachment(HandoverExportWorkbook book, FileRef reference) {
        // FileRef.type is optional and client supplied; storage metadata decides whether to embed.
        // A missing attachment is an explicit task failure, never a silently omitted photograph.
        return files.getFile(reference.getFileId())
                .switchIfEmpty(Mono.error(new IOException("Attachment is missing: " + reference.getFileId())))
                .flatMap(info -> "image".equals(info.mediaType().getType())
                        ? photo(book, reference.getFileId(), reference.getPhotoType())
                        : Mono.empty());
    }

    private Mono<Void> photo(HandoverExportWorkbook book, String id, String type) {
        Path image = book.directory.resolve("source-image");
        AtomicLong size = new AtomicLong();
        // No accumulated byte[]: each file is streamed, bounded and removed before requesting the
        // next.
        Flux<DataBuffer> data =
                files.read(id)
                        .handle(
                                (buffer, sink) -> {
                                    if (size.addAndGet(buffer.readableByteCount())
                                            > HandoverExportWorkbook.MAX_IMAGE_BYTES) {
                                        DataBufferUtils.release(buffer);
                                        sink.error(new IOException("Photo exceeds 32 MiB: " + id));
                                    } else sink.next(buffer);
                                });
        // 只读取已授权记录中保存的文件引用，发布前还会按来源清单复核记录权限。
        return DataBufferUtils.write(
                        data, image, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                .then(runIo(() -> book.picture(image, type)))
                .timeout(Duration.ofSeconds(60))
                .onErrorMap(
                        error ->
                                new IOException(
                                        "Cannot export photo " + id + ": " + error.getMessage(),
                                        error))
                .then(runIo(() -> Files.deleteIfExists(image)));
    }

    private Flux<DataBuffer> read(Path path) {
        return DataBufferUtils.read(path, DefaultDataBufferFactory.sharedInstance, 64 * 1024);
    }

    private Mono<Void> save(HandoverExportEntity task, HandoverExportWorkbook book) {
        // Save both private files before committing success; compensation covers DB failure and
        // cancel.
        return Mono.usingWhen(
                Mono.just(new SavedFiles()),
                saved ->
                        files.saveFile(task.getFileName(), read(book.workbook))
                                .flatMap(
                                        file -> {
                                            saved.workbook = file.getId();
                                            return files.saveFile(
                                                    "handover-export-" + task.getId() + ".sources",
                                                    read(book.manifest));
                                        })
                                .flatMap(
                                        manifest -> {
                                            saved.manifest = manifest.getId();
                                            return complete(task, saved, book.manifest);
                                        })
                                .doOnSuccess(ignore -> saved.committed = true),
                saved -> compensate(saved),
                (saved, error) -> compensate(saved),
                this::compensate);
    }

    private static final class SavedFiles {
        String workbook, manifest;
        boolean committed;
    }

    private Mono<Void> compensate(SavedFiles saved) {
        if (saved.committed) return Mono.empty();
        return Flux.just(Objects.toString(saved.workbook, ""), Objects.toString(saved.manifest, ""))
                .filter(id -> !id.isEmpty())
                .concatMap(files::delete)
                .then();
    }

    private Mono<Void> complete(HandoverExportEntity task, SavedFiles saved, Path manifest) {
        // 文件上传可能很慢，上传后以新授权复核全部来源，再将私有文件关联到可下载任务。
        var session = authorization.session(creator(task));
        return session.execute(() -> assertTaskScope(task))
                .then(verifyLocalManifest(task, manifest, session))
                .then(session.execute(() -> tasks.createUpdate()
                        .set("status", "completed")
                        .set("fileId", saved.workbook)
                        .set("manifestFileId", saved.manifest)
                        .set("finishTime", System.currentTimeMillis())
                        .set("leaseUntil", 0L)
                        .where("id", task.getId())
                        .and("status", "running")
                        .and("leaseOwner", owner)
                        .and("version", task.getVersion())
                        .execute()))
                .flatMap(n -> n == 1
                        ? Mono.<Void>empty()
                        : Mono.error(new IllegalStateException("Export completion lost its lease")))
                .doOnSuccess(ignore -> completed.incrementAndGet());
    }

    private Mono<Void> assertTaskScope(HandoverExportEntity task) {
        if ("ledger".equals(task.getKind()) && !HandoverRules.hasText(task.getProjectId())) {
            return rows.selector(task.getRequest(), 0, null).then();
        }
        return scope.project(task.getProjectId(), CrudAssetPermission.read);
    }

    private Mono<Void> verifyLocalManifest(
            HandoverExportEntity task, Path path, HandoverExportScope.Session session) {
        return Flux.using(
                        () -> Files.lines(path),
                        stream -> Flux.fromStream(stream),
                        java.util.stream.Stream::close)
                .subscribeOn(Schedulers.boundedElastic())
                .buffer(HandoverExportRows.PAGE_SIZE)
                .concatMap(
                        ids -> session.execute(() -> rows.verify(task.getRequest(), ids)),
                        1)
                .then();
    }

    public Mono<HandoverExportEntity> authorizeDownload(String id) {
        return visible(id)
                .flatMap(
                        task -> {
                            HandoverRules.require(
                                    "completed".equals(task.getStatus())
                                            && task.getManifestFileId() != null,
                                    "export_not_ready");
                            return Mono.usingWhen(
                                    io(
                                            () ->
                                                    Files.createTempFile(
                                                            "handover-download-", ".sources")),
                                    path ->
                                            DataBufferUtils.write(
                                                            files.read(task.getManifestFileId()),
                                                            path,
                                                            StandardOpenOption.TRUNCATE_EXISTING)
                                                    .then(verifyLocalManifest(
                                                            task, path, authorization.session(creator(task))))
                                                    .thenReturn(task),
                                    path -> runIo(() -> Files.deleteIfExists(path)),
                                    (path, error) -> runIo(() -> Files.deleteIfExists(path)),
                                    path -> runIo(() -> Files.deleteIfExists(path)));
                        });
    }

    public Flux<DataBuffer> download(HandoverExportEntity task) {
        return files.read(task.getFileId());
    }

    public int active() {
        return active.get();
    }

    public long completed() {
        return completed.get();
    }

    public long failed() {
        return failed.get();
    }

    public long claimed() {
        return claimed.get();
    }

    public String lastError() {
        return lastError;
    }
}
