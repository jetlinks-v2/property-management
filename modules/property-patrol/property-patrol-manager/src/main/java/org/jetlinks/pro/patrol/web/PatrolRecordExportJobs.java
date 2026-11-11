package org.jetlinks.pro.patrol.web;

import jakarta.annotation.PreDestroy;
import org.hswebframework.web.authorization.ReactiveAuthenticationHolder;
import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.web.exception.NotFoundException;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.integration.PatrolProjectContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 单实例巡更导出任务所有者：请求快速返回编号，工作订阅显式保留调用者响应式上下文。
 * 最多2个并发、8份任务；完成文件保留1小时，定时清理和服务关闭均释放临时文件。
 * 下载重新校验源计划、图片权限；用户/应用/项目上下文不同不能访问任务及文件。
 */
@Component
@ManagedResource(objectName = "org.jetlinks:type=PatrolRecordExports")
public class PatrolRecordExportJobs {
    private static final long RETENTION = Duration.ofHours(1).toMillis();
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final Semaphore permits = new Semaphore(2);
    private volatile boolean closed;
    public record Status(String id, String state, int processedGroups, int totalGroups, String errorCode) {}

    static final class Job {
        final String id = UUID.randomUUID().toString();
        final String userId, application, project;
        final Disposable.Swap subscription = Disposables.swap();
        final AtomicInteger processed = new AtomicInteger();
        final AtomicBoolean permitReleased = new AtomicBoolean();
        volatile int total;
        volatile String state = "running", errorCode;
        volatile long finishedAt;
        volatile Path file;
        volatile List<String> plans = List.of();
        final Set<String> images = ConcurrentHashMap.newKeySet();
        Job(String userId, PatrolProjectContext context) {
            this.userId = userId; application = context.scopeKey(); project = context.selectedProject();
        }
        Status status() { return new Status(id, state, processed.get(), total, errorCode); }
    }

    public Mono<Status> start(Function<Job, Mono<byte[]>> generate) {
        return Mono.deferContextual(context -> Mono.zip(ReactiveAuthenticationHolder.get(), PatrolProjectContext.current())
                .map(pair -> {
                    var job = allocate(pair.getT1().getUser().getId(), pair.getT2());
                    // 唯一后台订阅由组件管理；运行期间传递认证/资产上下文，结束即释放，不将凭据写入文件或任务元数据。
                    job.subscription.update(Mono.defer(() -> generate.apply(job))
                            .flatMap(bytes -> Mono.fromCallable(() -> save(job, bytes)).subscribeOn(Schedulers.boundedElastic()))
                            .timeout(Duration.ofMinutes(45))
                            .as(MonoTracer.create("/patrol/records/export-job"))
                            .doFinally(signal -> { release(job); job.subscription.dispose(); })
                            .contextWrite(context)
                            .subscribeOn(Schedulers.boundedElastic())
                            .subscribe(path -> { job.finishedAt = System.currentTimeMillis(); job.state = "ready"; },
                                    error -> { job.errorCode = error instanceof BusinessException business ? business.getI18nCode()
                                            : "error.patrol_record_export_failed"; job.finishedAt = System.currentTimeMillis(); job.state = "failed"; }));
                    return job.status();
                }));
    }

    private synchronized Job allocate(String userId, PatrolProjectContext context) {
        if (closed || jobs.size() >= 8 || !permits.tryAcquire())
            throw new BusinessException("error.patrol_record_export_busy");
        var job = new Job(userId, context); jobs.put(job.id, job); return job;
    }

    private Path save(Job job, byte[] bytes) throws java.io.IOException {
        // 与关闭/过期删除串行，取消后到达的文件写入不能留下无所有者的临时文件。
        synchronized (job) {
            if (jobs.get(job.id) != job) throw new java.util.concurrent.CancellationException();
            Path file = Files.createTempFile("patrol-records-", ".xlsx");
            job.file = file;
            try { return Files.write(file, bytes); }
            catch (java.io.IOException error) { Files.deleteIfExists(file); job.file = null; throw error; }
        }
    }

    public Mono<Status> status(String id) { return owned(id).map(Job::status); }

    public Mono<FileSystemResource> download(String id) {
        return owned(id).flatMap(job -> {
            if (!"ready".equals(job.state)) return Mono.error(new BusinessException("error.patrol_record_export_not_ready"));
            // 文件生成以后也不能绕过最新项目/资产权限；图片权限不因为记录可读而自动授予。
            return (job.images.isEmpty() ? Mono.<Void>empty() : ReactiveAuthenticationHolder.get()
                    .filter(auth -> auth.hasPermission("patrol-attachment", "query"))
                    .switchIfEmpty(Mono.error(org.hswebframework.web.authorization.exception.AccessDenyException.NoStackTrace::new)).then())
                    .then(assertReadable(PatrolAssetType.patrolPlan, job.plans))
                    .then(assertReadable(PatrolAssetType.patrolAttachment, List.copyOf(job.images)))
                    .thenReturn(new FileSystemResource(job.file));
        });
    }

    private Mono<Void> assertReadable(PatrolAssetType type, List<String> ids) {
        // 小批次、有限并发校验，避免下载前串行RPC随记录数量累积成长请求。
        return Flux.fromIterable(ids).buffer(200)
                .flatMap(batch -> AssetsHolder.assertPermission(type, batch, CrudAssetPermission.read), 4).then();
    }

    private Mono<Job> owned(String id) {
        return Mono.zip(ReactiveAuthenticationHolder.get(), PatrolProjectContext.current())
                .flatMap(pair -> Mono.justOrEmpty(jobs.get(id)).filter(job ->
                        job.userId.equals(pair.getT1().getUser().getId()) && job.application.equals(pair.getT2().scopeKey())
                                && Objects.equals(job.project, pair.getT2().selectedProject())
                                && (job.finishedAt == 0 || job.finishedAt + RETENTION > System.currentTimeMillis())))
                .switchIfEmpty(Mono.error(NotFoundException::new));
    }

    @Scheduled(fixedDelay = 60000)
    public Mono<Void> cleanup() {
        return Flux.defer(() -> Flux.fromIterable(jobs.values()))
                .filter(job -> job.finishedAt > 0 && job.finishedAt + RETENTION <= System.currentTimeMillis())
                .flatMap(job -> Mono.fromRunnable(() -> remove(job)).subscribeOn(Schedulers.boundedElastic())).then();
    }
    private void release(Job job) { if (job.permitReleased.compareAndSet(false, true)) permits.release(); }

    private void remove(Job job) {
        if (!jobs.remove(job.id, job)) return;
        job.subscription.dispose();
        // 调度订阅尚未开始时取消也必须释放名额，不能只依赖源流的 doFinally。
        release(job);
        synchronized (job) {
            if (job.file != null) {
                try { Files.deleteIfExists(job.file); }
                catch (java.io.IOException error) { job.file.toFile().deleteOnExit(); }
            }
        }
    }
    @PreDestroy public synchronized void close() { closed = true; List.copyOf(jobs.values()).forEach(this::remove); }
    @ManagedAttribute public int getRunningCount() { return 2 - permits.availablePermits(); }
    @ManagedAttribute public int getRetainedCount() { return jobs.size(); }
}
