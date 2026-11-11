package org.jetlinks.pro.property.facility.service;

import static org.jetlinks.pro.property.facility.service.FacilityScope.require;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.i18n.LocaleUtils;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.DeviceEntity;
import org.jetlinks.pro.property.finance.command.*;
import org.jetlinks.pro.property.finance.meter.MeterBillingModels;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;

/** Bounded per-page previews and explicit per-row confirmations; a failed row remains visible and retryable. */
@Service @RequiredArgsConstructor
public class FacilityMeterBilling {
  private final FacilityScope scope;
  private final FacilityRecords records;
  private final DeviceStore devices;
  public record Preview(String projectId, String month, String receivableDate, String dueDate, QueryParamEntity query) {}
  public record Row(String id, String deviceName, String meterType, MeterBillingModels.Result result, String error) {}

  public Mono<PagerResult<Row>> preview(Preview input) {
    var q = input.query() == null ? new QueryParamEntity() : input.query();
    q.setPaging(true); q.setPageSize(Math.min(20, Math.max(1, q.getPageSize()))); q.setPageIndex(Math.max(0,q.getPageIndex()));
    q.toNestQuery(n -> n.and("projectId", input.projectId()).in("meterType", List.of("water","electricity")));
    return records.assertProject(input.projectId(), CrudAssetPermission.read)
      .then(scope.query(q, FacilityAssetType.facilityDevice, "id")).flatMap(devices::queryPager)
      .flatMap(page -> Flux.fromIterable(page.getData()).concatMap(d -> process(command(d.getId(), input), false, d))
        .collectList().map(rows -> {
          var result = new PagerResult<Row>(); result.setData(rows); result.setTotal(page.getTotal());
          result.setPageIndex(page.getPageIndex()); result.setPageSize(page.getPageSize()); return result;
        })).as(MonoTracer.create("property.facility.meter.preview"));
  }
  private ProcessMeterBillCommand command(String id, Preview input) {
    var c = new ProcessMeterBillCommand(); c.setDeviceId(id); c.setMonth(input.month());
    c.setReceivableDate(input.receivableDate()); c.setDueDate(input.dueDate()); return c;
  }
  public Mono<List<Row>> generate(List<ProcessMeterBillCommand> inputs) {
    require(inputs != null && !inputs.isEmpty() && inputs.size() <= 100 &&
      inputs.stream().map(ProcessMeterBillCommand::getDeviceId).distinct().count() == inputs.size(), "too_many");
    return Flux.fromIterable(inputs).concatMap(input -> process(input, true, null)).collectList();
  }
  private Mono<Row> process(ProcessMeterBillCommand command, boolean confirm, DeviceEntity device) {
    command.setConfirm(confirm);
    return CommandSupportManagerProviders.getCommandSupport(QueryMeterStandardsCommand.SERVICE_ID)
      .flatMap(s -> s.execute(command)).map(r -> new Row(command.getDeviceId(), r.deviceName(), r.energyType(), r, null))
      .onErrorResume(error -> Mono.just(new Row(command.getDeviceId(), device == null ? command.getDeviceId() : device.getName(),
        device == null ? null : device.getMeterType(), null, LocaleUtils.resolveMessage(error.getMessage(), error.getMessage()))));
  }
}
