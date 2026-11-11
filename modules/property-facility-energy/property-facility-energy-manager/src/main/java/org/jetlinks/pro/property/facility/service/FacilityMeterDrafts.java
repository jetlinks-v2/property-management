package org.jetlinks.pro.property.facility.service;

import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.i18n.LocaleUtils;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.finance.command.*;
import org.jetlinks.pro.property.finance.meter.MeterBillingModels;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;

/** Bounded monthly preparation never confirms finance bills. Draft failures remain persisted and visible. */
@Service @RequiredArgsConstructor
public class FacilityMeterDrafts {
  private final MeterBillStore bills;
  private final FacilityScope scope;
  private final FacilityRecords records;

  public Mono<MeterBillEntity> prepare(DeviceEntity device, YearMonth month) {
    String id=FacilityRecords.stableId(device.getTenantId(),device.getApplicationId(),device.getId(),month.toString(),"meter-bill");
    return bills.findById(id).switchIfEmpty(Mono.defer(() -> scope.current().flatMap(c -> {
      var row=FacilityScope.initialize(new MeterBillEntity(),c); FacilityScope.inherit(row,device); row.setId(id);
      row.setDeviceId(device.getId()); row.setDeviceName(device.getName()); row.setMeterType(device.getMeterType()); row.setMonth(month.toString());
      row.setReceivableDate(month.plusMonths(1).atDay(1).toString()); row.setDueDate(month.plusMonths(1).atEndOfMonth().toString());
      return scope.assertRecord(device,FacilityAssetType.facilityDevice,device.getId(),CrudAssetPermission.read)
        .then(calculate(row)).flatMap(b -> bills.insert(b).thenReturn(b));
    }))).as(MonoTracer.create("property.facility.meter.monthly.prepare"));
  }

  Mono<MeterBillEntity> read(String id, CrudAssetPermission permission) {
    return bills.findById(id).switchIfEmpty(Mono.error(new org.hswebframework.web.exception.NotFoundException()))
      .flatMap(row -> records.assertProject(row.getProjectId(),permission)
        .then(scope.assertRecord(row,FacilityAssetType.facilityDevice,row.getDeviceId(),permission)).thenReturn(row));
  }

  /** Recalculation is explicit; scheduler never silently refreshes an already reviewed or rejected snapshot. */
  Mono<MeterBillEntity> calculate(MeterBillEntity row) {
    return process(row,false).map(result -> apply(row,result))
      .onErrorResume(e -> { row.setState("error"); row.setError(error(e)); return Mono.just(row); });
  }

  Mono<MeterBillingModels.Result> process(MeterBillEntity row, boolean confirm) {
    var command=new ProcessMeterBillCommand(); command.setDeviceId(row.getDeviceId()); command.setMonth(row.getMonth());
    command.setReceivableDate(row.getReceivableDate()); command.setDueDate(row.getDueDate()); command.setConfirm(confirm);
    if(row.getResult()!=null) command.setFingerprint(row.getResult().fingerprint());
    return CommandSupportManagerProviders.getCommandSupport(QueryMeterStandardsCommand.SERVICE_ID)
      .switchIfEmpty(Mono.error(new org.hswebframework.web.exception.BusinessException("error.facility.meter_finance_unavailable",503)))
      .flatMap(s -> s.execute(command));
  }

  static MeterBillEntity apply(MeterBillEntity row, MeterBillingModels.Result result) {
    row.setResult(result); row.setFullName(result.fullName()); row.setCustomerName(result.customerName());
    row.setQuantity(result.quantity()); row.setAmount(result.amount()); row.setError(null);
    row.setState(result.billId()==null?"pending":"approved");
    if(result.detail()!=null) {
      row.setReceivableDate(result.detail().receivableDate()); row.setDueDate(result.detail().dueDate());
      if(result.billId()!=null) { row.setReviewerName(result.detail().reviewedName()); row.setReviewedTime(result.detail().reviewedTime()); }
    }
    return row;
  }
  static String error(Throwable e) {
    String key=Objects.toString(e.getMessage(),e.getClass().getSimpleName());
    String text=LocaleUtils.resolveMessage(key,key);
    return text.substring(0,Math.min(1800,text.length()));
  }
}
