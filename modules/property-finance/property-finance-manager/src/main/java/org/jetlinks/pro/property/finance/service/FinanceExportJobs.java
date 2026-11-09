package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.alibaba.fastjson.JSON;

import lombok.extern.slf4j.Slf4j;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.*;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.FinanceExportTaskEntity;
import org.jetlinks.pro.property.finance.report.*;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import reactor.core.Disposable;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** 持久导出队列单消费者；每页背压读取、同一REPEATABLE_READ快照、租约恢复、受权下载。 */
@Service
@Slf4j
public class FinanceExportJobs implements SmartLifecycle {
    private final FinanceScope scope;
    private final ReactiveRepository<FinanceExportTaskEntity, String> tasks;
    private final ReportExportPages pages;
    private final ReactiveTransactionManager transactions;
    private final Path root;
    private final java.util.function.Function<String, Mono<Authentication>> authentications;
    private Disposable subscription;
    private volatile boolean running;

    @org.springframework.beans.factory.annotation.Autowired
    public FinanceExportJobs(
            FinanceScope scope,
            ReactiveRepository<FinanceExportTaskEntity, String> tasks,
            ReportExportPages pages,
            ReactiveTransactionManager transactions,
            @Value("${property.finance.export-directory:data/finance-exports}") String directory) {
        this(scope, tasks, pages, transactions, directory, ReactiveAuthenticationHolder::get);
    }

    FinanceExportJobs(
            FinanceScope scope,
            ReactiveRepository<FinanceExportTaskEntity, String> tasks,
            ReportExportPages pages,
            ReactiveTransactionManager transactions,
            String directory,
            java.util.function.Function<String, Mono<Authentication>> authentications) {
        this.scope = scope;
        this.tasks = tasks;
        this.pages = pages;
        this.transactions = transactions;
        this.root = Path.of(directory).toAbsolutePath().normalize();
        this.authentications = authentications;
    }

    public Mono<FinanceExportTaskEntity> create(
            String kind, ReportExportInput input, String format) {
        return Mono.defer(
                () -> {
                    if (!Set.of("excel", "pdf").contains(format))
                        return Mono.error(error("report_query_invalid", 400));
                    String json = JSON.toJSONString(input);
                    List<String> projects = projects(kind, json);
                    if (json.length() > 60000)
                        return Mono.error(error("report_query_invalid", 400));
                    return scope.authenticated()
                            .flatMap(
                                    auth ->
                                            Flux.fromIterable(projects)
                                                    .concatMap(
                                                            p ->
                                                                    scope.project(
                                                                            p,
                                                                            CrudAssetPermission
                                                                                    .read))
                                                    .then(
                                                            FinanceJobAuthority.capture(
                                                                    auth,
                                                                    "report_export_authority"))
                                                    .flatMap(
                                                            saved ->
                                                                    TenantHolder.currentId()
                                                                            .defaultIfEmpty("")
                                                                            .flatMap(
                                                                                    tenant -> {
                                                                                        var task =
                                                                                                new FinanceExportTaskEntity();
                                                                                        InvoiceCompanyService
                                                                                                .init(
                                                                                                        task,
                                                                                                        auth);
                                                                                        task.setId(
                                                                                                UUID.randomUUID()
                                                                                                        .toString());
                                                                                        task
                                                                                                .setOwnerId(
                                                                                                        auth.getUser()
                                                                                                                .getId());
                                                                                        task
                                                                                                .setTenantId(
                                                                                                        tenant);
                                                                                        task
                                                                                                .setExecutorSnapshot(
                                                                                                        saved);
                                                                                        task
                                                                                                .setReport(
                                                                                                        kind);
                                                                                        task
                                                                                                .setInputJson(
                                                                                                        json);
                                                                                        task
                                                                                                .setFormat(
                                                                                                        format);
                                                                                        task
                                                                                                .setState(
                                                                                                        "QUEUED");
                                                                                        task
                                                                                                .setLeaseUntil(
                                                                                                        0L);
                                                                                        return tasks.insert(
                                                                                                        task)
                                                                                                .thenReturn(
                                                                                                        task);
                                                                                    })));
                });
    }

    public Mono<FinanceExportTaskEntity> status(String kind, String id) {
        return owned(kind, id);
    }

    private Mono<FinanceExportTaskEntity> owned(String kind, String id) {
        return scope.authenticated()
                .flatMap(
                        auth ->
                                TenantHolder.currentId()
                                        .defaultIfEmpty("")
                                        .flatMap(
                                                tenant ->
                                                        tasks.findById(id)
                                                                .filter(
                                                                        t ->
                                                                                t.getReport()
                                                                                                .equals(
                                                                                                        kind)
                                                                                        && t.getOwnerId()
                                                                                                .equals(
                                                                                                        auth.getUser()
                                                                                                                .getId())
                                                                                        && Objects
                                                                                                .equals(
                                                                                                        t
                                                                                                                .getTenantId(),
                                                                                                        tenant)
                                                                                        && !Boolean
                                                                                                .TRUE
                                                                                                .equals(
                                                                                                        t
                                                                                                                .getDeleted()))
                                                                .switchIfEmpty(
                                                                        Mono.error(
                                                                                error(
                                                                                        "report_export_missing",
                                                                                        404)))
                                                                .flatMap(
                                                                        t ->
                                                                                Flux.fromIterable(
                                                                                                projects(
                                                                                                        kind,
                                                                                                        t
                                                                                                                .getInputJson()))
                                                                                        .concatMap(
                                                                                                p ->
                                                                                                        scope
                                                                                                                .project(
                                                                                                                        p,
                                                                                                                        CrudAssetPermission
                                                                                                                                .read))
                                                                                        .then(
                                                                                                Mono
                                                                                                        .just(
                                                                                                                t)))));
    }

    public Mono<org.springframework.http.ResponseEntity<org.springframework.core.io.Resource>>
            downloadResponse(String kind, String id) {
        return download(kind, id)
                .map(
                        file -> {
                            boolean pdf =
                                    file.getFilename() != null
                                            && file.getFilename().endsWith(".pdf");
                            return org.springframework.http.ResponseEntity.ok()
                                    .contentType(
                                            org.springframework.http.MediaType.parseMediaType(
                                                    pdf
                                                            ? "application/pdf"
                                                            : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                                    .header(
                                            org.springframework.http.HttpHeaders
                                                    .CONTENT_DISPOSITION,
                                            "attachment; filename=report." + (pdf ? "pdf" : "xlsx"))
                                    .body(file);
                        });
    }

    public Mono<org.springframework.core.io.Resource> download(String kind, String id) {
        return owned(kind, id)
                .flatMap(
                        task -> {
                            if (!"DONE".equals(task.getState()))
                                return Mono.error(error("report_export_not_ready", 409));
                            Path file = root.resolve(task.getId() + "." + task.getFormat());
                            return Mono.fromCallable(
                                            () ->
                                                    Files.readAllLines(
                                                            root.resolve(task.getId() + ".scope")))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .flatMap(
                                            spaces ->
                                                    Flux.fromIterable(spaces)
                                                            .filter(s -> !s.isBlank())
                                                            .concatMap(
                                                                    s ->
                                                                            AssetsHolder
                                                                                    .assertPermission(
                                                                                            AssetType
                                                                                                    .of(
                                                                                                            "space"),
                                                                                            s,
                                                                                            CrudAssetPermission
                                                                                                    .read))
                                                            .then(
                                                                    Mono.just(
                                                                            (org.springframework
                                                                                            .core.io
                                                                                            .Resource)
                                                                                    new org
                                                                                            .springframework
                                                                                            .core.io
                                                                                            .FileSystemResource(
                                                                                            file))));
                        });
    }

    static List<String> projects(String kind, String json) {
        if (kind.equals("receivables")) {
            var i = JSON.parseObject(json, ReceivableReportInput.class);
            i.validate();
            return i.getProjectIds();
        }
        if (kind.equals("received")) {
            var i = JSON.parseObject(json, ReceivedReportInput.class);
            i.validate();
            return i.getProjectIds();
        }
        if (kind.equals("invoices")) {
            var i = JSON.parseObject(json, InvoiceReportInput.class);
            i.validate();
            return i.getProjectIds();
        }
        var i = JSON.parseObject(json, AnalysisInput.class);
        i.validate(kind);
        return i.getProjectIds();
    }

    static String permission(String kind) {
        return kind.equals("receivables")
                ? "property-finance-report-receivable"
                : kind.equals("invoices")
                        ? "property-finance-report-invoice"
                        : "property-finance-report-" + kind;
    }

    Mono<Void> poll() {
        return tasks.createQuery()
                .in("state", List.of("QUEUED", "RUNNING"))
                .lte("leaseUntil", System.currentTimeMillis())
                .fetchOne()
                .flatMap(this::claim)
                .contextWrite(ignored -> Context.empty());
    }

    private Mono<Void> claim(FinanceExportTaskEntity t) {
        String owner = UUID.randomUUID().toString();
        return tasks.createUpdate()
                .set("state", "RUNNING")
                .set("leaseOwner", owner)
                .set("leaseUntil", System.currentTimeMillis() + 900_000)
                .where("id", t.getId())
                .in("state", List.of("QUEUED", "RUNNING"))
                .lte("leaseUntil", System.currentTimeMillis())
                .execute()
                .filter(n -> n == 1)
                .flatMap(
                        n -> {
                            t.setLeaseOwner(owner);
                            var tenant =
                                    t.getTenantId() == null || t.getTenantId().isBlank()
                                            ? Context.empty()
                                            : TenantHolder.makeCurrent(t.getTenantId());
                            return authentications
                                    .apply(
                                            FinanceJobAuthority.executorId(
                                                    t.getExecutorSnapshot(),
                                                    "report_export_authority"))
                                    .switchIfEmpty(
                                            Mono.error(error("report_export_authority", 403)))
                                    .flatMap(
                                            auth ->
                                                    FinanceJobAuthority.restore(
                                                            t.getExecutorSnapshot(),
                                                            t.getTenantId(),
                                                            auth,
                                                            a ->
                                                                    a.hasPermission(
                                                                            permission(
                                                                                    t.getReport()),
                                                                            "export"),
                                                            "report_export_authority"))
                                    .flatMap(context -> execute(t).contextWrite(ignored -> context))
                                    .contextWrite(ignored -> tenant)
                                    .then(
                                            tasks.createUpdate()
                                                    .set("state", "DONE")
                                                    .set("executorSnapshot", "")
                                                    .where("id", t.getId())
                                                    .and("leaseOwner", owner)
                                                    .execute())
                                    .then()
                                    .onErrorResume(
                                            failure -> {
                                                log.warn(
                                                        "Finance report export failed: {}",
                                                        t.getId(),
                                                        failure);
                                                return tasks.createUpdate()
                                                        .set("state", "FAILED")
                                                        .set("executorSnapshot", "")
                                                        .set("errorMessage", safeFailure(failure))
                                                        .where("id", t.getId())
                                                        .and("leaseOwner", owner)
                                                        .execute()
                                                        .then();
                                            });
                        })
                .then();
    }

    private static String safeFailure(Throwable failure) {
        Throwable cause = reactor.core.Exceptions.unwrap(failure);
        if (cause instanceof org.hswebframework.web.exception.BusinessException business) {
            String key = business.getI18nCode();
            if (key != null && key.startsWith("error.property_finance_report_"))
                return org.hswebframework.web.i18n.LocaleUtils.resolveMessage(
                        key, business.getMessage(), business.getArgs());
        }
        return org.hswebframework.web.i18n.LocaleUtils.resolveMessage(
                "error.property_finance_report_export_failed", "报表生成失败，请重试或联系管理员。");
    }

    private Mono<Void> execute(FinanceExportTaskEntity task) {
        var definition = new DefaultTransactionDefinition();
        definition.setReadOnly(true);
        definition.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        definition.setTimeout(840);
        return Mono.usingWhen(
                Mono.fromCallable(
                                () -> new StreamingReportFile(root, task.getFormat().equals("pdf")))
                        .subscribeOn(Schedulers.boundedElastic()),
                writer -> {
                    var spec = JSON.parseObject(task.getInputJson());
                    String mode = spec.getString("exportMode");
                    boolean detail = "DETAIL".equals(mode);
                    Mono<Void> read = writePages(task, writer, 0, detail, true);
                    if ("BOTH".equals(mode) && !"DETAIL".equals(spec.getString("groupBy")))
                        read = read.then(writePages(task, writer, 0, true, true));
                    // 汇总也扫描明细收集空间授权清单，下载时权限缩小不能泄漏之前缓存的文件。
                    if (!detail
                            && !"BOTH".equals(mode)
                            && !"DETAIL".equals(spec.getString("groupBy")))
                        read = read.then(writePages(task, writer, 0, true, false));
                    return read.as(
                                    TransactionalOperator.create(transactions, definition)
                                            ::transactional)
                            .then(
                                    Mono.fromCallable(
                                                    () -> {
                                                        writer.finish(
                                                                task.getId(),
                                                                task.getFormat(),
                                                                "报表："
                                                                        + ReportExportPages.title(
                                                                                task.getReport())
                                                                        + "\n生成时间："
                                                                        + java.time.LocalDateTime
                                                                                .now()
                                                                        + "\n筛选："
                                                                        + task.getInputJson());
                                                        return true;
                                                    })
                                            .subscribeOn(Schedulers.boundedElastic()))
                            .then();
                },
                writer ->
                        Mono.fromRunnable(
                                        () -> {
                                            try {
                                                writer.close();
                                            } catch (java.io.IOException e) {
                                                throw new java.io.UncheckedIOException(e);
                                            }
                                        })
                                .subscribeOn(Schedulers.boundedElastic()));
    }

    private Mono<Void> writePages(
            FinanceExportTaskEntity t,
            StreamingReportFile writer,
            int index,
            boolean detail,
            boolean write) {
        return pages.read(t.getReport(), t.getInputJson(), index, detail)
                .flatMap(
                        page ->
                                Mono.fromRunnable(() -> writer.append(page, write, index == 0))
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .then(
                                                Mono.defer(
                                                        () -> {
                                                            if ((long) (index + 1) * 200
                                                                    >= page.total())
                                                                return Mono.empty();
                                                            return writePages(
                                                                    t, writer, index + 1, detail,
                                                                    write);
                                                        })));
    }

    @Override
    public boolean isAutoStartup() {
        return false;
    }

    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        subscription =
                Flux.interval(Duration.ofSeconds(3))
                        .onBackpressureDrop()
                        .concatMap(
                                tick ->
                                        poll().onErrorResume(
                                                        failure -> {
                                                            log.warn(
                                                                    "Finance export queue poll"
                                                                        + " failed",
                                                                    failure);
                                                            return Mono.empty();
                                                        }))
                        .subscribe();
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (subscription != null) subscription.dispose();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
