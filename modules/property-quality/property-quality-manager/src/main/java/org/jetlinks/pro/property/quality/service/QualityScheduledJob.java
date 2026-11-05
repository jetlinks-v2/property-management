package org.jetlinks.pro.property.quality.service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.context.SmartLifecycle;
import org.jetlinks.pro.plugin.context.ClusterPluginScheduler;
import org.jetlinks.core.trace.MonoTracer;
import reactor.core.*;
import reactor.core.publisher.*;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import javax.management.*;
import java.lang.management.ManagementFactory;
/** 集群单执行节点轮询，持久化批次CAS及唯一约束兜住重启与调度重叠。失败计划保持可重试。 */
@Service @Slf4j @RequiredArgsConstructor
public class QualityScheduledJob implements SmartLifecycle,QualityScheduledJobMBean {
 private final QualityNotifications notifications;private final QualityScheduleService schedules;private final QualityRecordService records;private final QualityGeneration generation;
 private final QualityExportService exports;private final QualityExportManager exportManager;private final QualityExecution execution;
 private final ClusterPluginScheduler scheduler=new ClusterPluginScheduler("property-quality");
 private final AtomicBoolean polling=new AtomicBoolean();private final AtomicLong generated=new AtomicLong(),failures=new AtomicLong();
 private volatile boolean running;private volatile long completed;private Disposable task;private ObjectName objectName;
 Mono<Void> poll(){return Mono.defer(()->{
  if(!polling.compareAndSet(false,true))return Mono.empty();long now=System.currentTimeMillis();
  return schedules.createQuery().where("status",1).lte("scheduledTime",now).orderBy(org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder.asc("scheduledTime")).fetch().take(200)
   .concatMap(row->execution.asOwner(row,()->generation.generate(row)).doOnSuccess(v->generated.incrementAndGet()).onErrorResume(e->{failures.incrementAndGet();log.warn("Quality generation failed: schedule={}, type={}",row.getId(),e.getClass().getSimpleName(),e);return Mono.empty();})).then()
   .then(records.createUpdate().set("recordStatus",3).where("recordStatus",1).lt("reportDeadline",now).execute())
   .thenMany(exports.createQuery().in("status",java.util.List.of(0,1)).lte("leaseUntil",now).fetch().take(5))
   .concatMap(row->exports.createUpdate().set("status",1).set("leaseUntil",now+600000).where("id",row.getId()).and("status",row.getStatus()).and("leaseUntil",row.getLeaseUntil()).execute().filter(n->n==1)
    .flatMap(n->execution.asOwner(row,()->exportManager.generate(row))).onErrorResume(e->{failures.incrementAndGet();log.warn("Quality export failed: id={}, type={}",row.getId(),e.getClass().getSimpleName(),e);return exports.createUpdate().set("status",3).set("failureReason",e.getClass().getSimpleName()).where("id",row.getId()).execute().then();}))
   .then(notifications.poll()).doOnSuccess(v->completed=System.currentTimeMillis()).doFinally(s->polling.set(false))
   .as(MonoTracer.create("/quality/scheduled"));
 });}
 public synchronized void start(){if(running)return;task=scheduler.interval("quality-jobs",Mono.defer(this::poll),Duration.ofSeconds(10),true);running=true;try{objectName=new ObjectName("org.jetlinks:type=PropertyQuality,name=ScheduledJob");ManagementFactory.getPlatformMBeanServer().registerMBean(new StandardMBean(this,QualityScheduledJobMBean.class),objectName);}catch(Exception e){log.warn("Quality MBean registration failed",e);}}
 public synchronized void stop(){running=false;if(task!=null)task.dispose();if(objectName!=null)try{ManagementFactory.getPlatformMBeanServer().unregisterMBean(objectName);}catch(Exception e){log.warn("Quality MBean unregister failed",e);}objectName=null;}
 public boolean isRunning(){return running;}public boolean isPolling(){return polling.get();}public long getGeneratedCount(){return generated.get();}public long getFailureCount(){return failures.get();}public long getLastCompletedTime(){return completed;}
}
