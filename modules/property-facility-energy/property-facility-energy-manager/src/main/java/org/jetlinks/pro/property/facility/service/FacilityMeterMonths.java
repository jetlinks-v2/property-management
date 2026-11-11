package org.jetlinks.pro.property.facility.service;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;

/** Keyset pages cover every configuration and device; preparing a month never creates a receivable. */
@Service @RequiredArgsConstructor
public class FacilityMeterMonths {
  private final MeterChargeStore rules;
  private final DeviceStore devices;
  private final FacilityScope scope;
  private final FacilityExecution execution;
  private final FacilityMeterDrafts drafts;
  private final AtomicBoolean running=new AtomicBoolean();
  private final AtomicLong prepared=new AtomicLong(), failures=new AtomicLong();
  private volatile long completed;
  private volatile String lastError;

  public Mono<Void> poll() {
    return Mono.defer(() -> {
      if(!running.compareAndSet(false,true)) return Mono.empty();
      return rulePage("").expand(page -> page.isEmpty()?Mono.empty():rulePage(page.get(page.size()-1).getId()))
        .concatMapIterable(page -> page).concatMap(rule -> execution.asOwner(rule,() -> prepare(rule))
          .onErrorResume(e -> { failure(e); return Mono.empty(); }))
        .then().doOnSuccess(v -> completed=System.currentTimeMillis()).doFinally(s -> running.set(false))
        .as(MonoTracer.create("property.facility.meter.monthly.scan"));
    });
  }
  private Mono<List<MeterChargeEntity>> rulePage(String after) {
    return rules.createQuery().where("deleted",false).and("enabled",true).gt("id",after)
      .orderBy(org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder.asc("id"))
      .paging(0,100).fetch().collectList();
  }
  private Mono<Void> prepare(MeterChargeEntity rule) {
    return devicePage(rule,"").expand(page -> page.isEmpty()?Mono.empty():devicePage(rule,page.get(page.size()-1).getId()))
      .concatMapIterable(page -> page).concatMap(device -> {
        var current=YearMonth.now(ZoneId.of("Asia/Shanghai"));
        return Flux.generate(() -> startMonth(rule,device), (month,sink) -> {
          if(!month.isBefore(current)) sink.complete(); else sink.next(month);
          return month.plusMonths(1);
        }).cast(YearMonth.class).concatMap(month -> drafts.prepare(device,month)
          .doOnNext(row -> { prepared.incrementAndGet(); if("error".equals(row.getState())) { failures.incrementAndGet(); lastError=row.getError(); } })
          .onErrorResume(e -> { failure(e); return Mono.empty(); })).then();
      }).then();
  }
  private static YearMonth startMonth(MeterChargeEntity rule,DeviceEntity device) {
    LocalDate earliest=LocalDate.parse(rule.getEffectiveFrom());
    for(var version:rule.getHistory()) if(version.effectiveFrom()!=null && LocalDate.parse(version.effectiveFrom()).isBefore(earliest))
      earliest=LocalDate.parse(version.effectiveFrom());
    if(device.getAllocationSince()!=null) {
      var allocation=Instant.ofEpochMilli(device.getAllocationSince()).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
      if(allocation.isAfter(earliest)) earliest=allocation;
    }
    return earliest.getDayOfMonth()==1?YearMonth.from(earliest):YearMonth.from(earliest).plusMonths(1);
  }
  private Mono<List<DeviceEntity>> devicePage(MeterChargeEntity rule,String after) {
    var q=QueryParamEntity.newQuery().where("projectId",rule.getProjectId()).and("meterType",rule.getEnergyType())
      .and("energyEnabled",true).and("projectConfirmed",true).gt("id",after).orderByAsc("id").getParam();
    q.setPageSize(100); q.setPageIndex(0); q.setPaging(true);
    return scope.query(q,FacilityAssetType.facilityDevice,"id").flatMapMany(p -> devices.createQuery().setParam(p).fetch()).collectList();
  }
  private void failure(Throwable e) { failures.incrementAndGet(); lastError=FacilityMeterDrafts.error(e); }
  public boolean isRunning() { return running.get(); }
  public long getCompleted() { return completed; }
  public long getPrepared() { return prepared.get(); }
  public long getFailures() { return failures.get(); }
  public String getLastError() { return lastError; }
}
