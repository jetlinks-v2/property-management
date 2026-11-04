package org.jetlinks.pro.smartpark.repair.service;

import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.plugin.core.PluginScheduler;
import org.jetlinks.pro.plugin.context.ClusterPluginScheduler;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.management.ObjectName;
import javax.management.StandardMBean;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 每分钟执行有界游标扫描；集群调度控制执行节点，工单 CAS 防止与人工动作冲突。 */
@Service
@Slf4j
public class RepairScheduledJob implements SmartLifecycle, RepairScheduledJobMBean {
    static final int BATCH_SIZE = 100;
    static final int MAX_BATCHES = 20;
    private final RepairOrderService orders;
    private final RepairScheduledService service;
    private final RepairNotificationService notifications;
    private final RepairQrUploadService qrUploads;
    private final PluginScheduler scheduler = new ClusterPluginScheduler("smart-park-repair-scheduled");
    private final AtomicBoolean polling = new AtomicBoolean();
    private final AtomicBoolean notificationPolling = new AtomicBoolean();
    private final AtomicLong scans = new AtomicLong(), changed = new AtomicLong(), failures = new AtomicLong();
    private volatile boolean running;
    private volatile long lastCompleted;
    private Disposable task;
    private Disposable notificationTask;
    private ObjectName objectName;

    public RepairScheduledJob(RepairOrderService orders, RepairScheduledService service, RepairNotificationService notifications, RepairQrUploadService qrUploads) {
        this.orders = orders;
        this.service = service;
        this.notifications = notifications;
        this.qrUploads = qrUploads;
    }

    private Mono<Void> pollNotifications() {
        return Mono.defer(() -> {
            if (!notificationPolling.compareAndSet(false,true)) return Mono.empty();
            return notifications.poll().onErrorResume(error -> failure("notifications",error))
                .doFinally(signal -> notificationPolling.set(false));
        });
    }

    Mono<Void> poll() {
        return Mono.defer(() -> {
            if (!polling.compareAndSet(false, true)) return Mono.empty();
            long now = System.currentTimeMillis();
            return Flux.just("dispatch", "evaluation", "timeout")
                .concatMap(kind -> scan(kind, "", 0, now)).then()
                .then(qrUploads.cleanup())
                .doOnSuccess(ignore -> lastCompleted = System.currentTimeMillis())
                .onErrorResume(error -> failure("scan", error))
                .doFinally(signal -> polling.set(false))
                .as(MonoTracer.create("/smart-park/repair/scheduled-scan"));
        });
    }

    private Mono<Void> scan(String kind, String afterId, int batch, long now) {
        if (batch >= MAX_BATCHES) return Mono.empty();
        QueryParamEntity query = QueryParamEntity.newQuery().where("id", "gt", afterId)
            .orderByAsc("id").getParam();
        query.setPageIndex(0);
        query.setPageSize(BATCH_SIZE);
        switch (kind) {
            case "dispatch" -> query.toQuery().and("status", 0);
            case "evaluation" -> query.toQuery().and("status", "in", List.of(2, 5, 8))
                .and("finishTime", "lte", now - 7 * 86_400_000L)
                .nest().isNull("ownerEvaluated").or("ownerEvaluated", false).end();
            case "timeout" -> query.toQuery().and("status", 1).and("finishDeadline", "lt", now)
                .nest().isNull("timedOut").or("timedOut", false).end();
            default -> throw new IllegalArgumentException("Unknown scheduled repair scan");
        }
        // 每批最多100条且最多20批；使用ID游标，前一批改变状态不会造成翻页漏单。
        return orders.createQuery().setParam(query).fetch().collectList()
            .flatMap(rows -> Flux.fromIterable(rows).concatMap(order -> execute(order, kind, now)).then(
                rows.size() == BATCH_SIZE ? Mono.defer(() -> scan(kind, rows.get(rows.size() - 1).getId(), batch + 1, now)) : Mono.empty()));
    }

    private Mono<Void> execute(RepairOrderEntity order, String kind, long now) {
        scans.incrementAndGet();
        return service.execute(order.getId(), kind, now)
            .doOnNext(updated -> { if (updated) changed.incrementAndGet(); }).then()
            .onErrorResume(error -> failure(kind, error));
    }

    private Mono<Void> failure(String kind, Throwable error) {
        failures.incrementAndGet();
        log.warn("Repair scheduled operation failed: kind={}, errorType={}", kind, error.getClass().getSimpleName(), error);
        return Mono.empty();
    }

    @Override public synchronized void start() {
        if (running) return;
        task = scheduler.interval("repair-scan", Mono.defer(this::poll), Duration.ofMinutes(1), true);
        notificationTask = scheduler.interval("repair-inside-mail", Mono.defer(this::pollNotifications), Duration.ofSeconds(5), true);
        running = true;
        try {
            var name = new ObjectName("org.jetlinks:type=SmartParkRepair,name=ScheduledJob");
            ManagementFactory.getPlatformMBeanServer().registerMBean(new StandardMBean(this, RepairScheduledJobMBean.class), name);
            objectName = name;
        } catch (Exception error) { log.warn("Unable to register repair scheduled MBean", error); }
    }

    @Override public synchronized void stop() {
        running = false;
        if (task != null) { task.dispose(); task = null; }
        if (notificationTask != null) { notificationTask.dispose(); notificationTask = null; }
        if (objectName != null) {
            try { ManagementFactory.getPlatformMBeanServer().unregisterMBean(objectName); }
            catch (Exception error) { log.warn("Unable to unregister repair scheduled MBean", error); }
            objectName = null;
        }
    }
    @Override public boolean isRunning() { return running; }
    @Override public boolean isPolling() { return polling.get(); }
    @Override public long getScanCount() { return scans.get(); }
    @Override public long getChangedCount() { return changed.get(); }
    @Override public long getFailureCount() { return failures.get(); }
    @Override public long getLastCompletedTime() { return lastCompleted; }
    @Override public long getNotificationSentCount() { return notifications.getSentCount(); }
    @Override public long getNotificationFailureCount() { return notifications.getFailureCount(); }
    @Override public long getNotificationPendingObserved() { return notifications.getPendingObserved(); }
    @Override public String getNotificationLastErrorType() { return notifications.getLastErrorType(); }
}
