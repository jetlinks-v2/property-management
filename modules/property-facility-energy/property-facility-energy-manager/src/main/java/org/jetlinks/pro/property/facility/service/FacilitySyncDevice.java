package org.jetlinks.pro.property.facility.service;

import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.sdk.server.device.DeviceInfo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

/** 单设备事务隔离同步失败；重复同步仅更新来源字段，不覆盖本地档案和能源计费关联。 */
@Service
@RequiredArgsConstructor
public class FacilitySyncDevice {
  private final DeviceStore store;
  private final DeviceAllocationStore allocations;
  private final FacilityDevices devices;
  private final FacilityDeviceMoves moves;
  private final FacilityRecords records;
  private final FacilityScope scope;

  public record Result(boolean created) {}

  @Transactional
  public Mono<Result> apply(DeviceAllocationEntity allocation, DeviceInfo source, List<String> candidates, long now) {
    // Re-read the allocation inside the write transaction: a stale batch must not move a newly reassigned device.
    return allocations.findById(allocation.getId()).doOnNext(current -> FacilityScope.require(
      Objects.equals(current.getVersion(), allocation.getVersion()) && Objects.equals(current.getProjectId(), allocation.getProjectId()), "conflict"))
      .switchIfEmpty(Mono.error(new org.hswebframework.web.exception.NotFoundException()))
      .then(allocations.createUpdate().set("version", allocation.getVersion()).where("id", allocation.getId())
        .and("version", allocation.getVersion()).execute()
        .doOnNext(count -> FacilityScope.require(count == 1, "conflict")))
      .then(store.findById(allocation.getId()).flatMap(found -> devices.writable(found.getId())).flatMap(row -> {
        FacilityScope.require(!Boolean.TRUE.equals(row.getDeleted()), "device_unavailable");
        return moves.validate(row, allocation.getProjectId())
          .then(Objects.equals(row.getProjectId(), allocation.getProjectId()) && Boolean.TRUE.equals(row.getProjectConfirmed())
            ? Mono.just(row) : devices.confirmProject(row.getId(), allocation.getProjectId(), row.getVersion()))
          .flatMap(current -> {
            long version = current.getVersion();
            FacilitySync.mergeSource(current, source, candidates, now);
            return records.update(store, current, version, "pendingProjectId").thenReturn(new Result(false));
          });
      }).switchIfEmpty(Mono.defer(() -> scope.current().flatMap(context -> {
        var row = FacilityScope.initialize(new DeviceEntity(), context);
        row.setId(allocation.getId()); row.setCloudDeviceId(source.getId()); row.setName(source.getName());
        row.setProjectId(allocation.getProjectId()); row.setOrganizationId(allocation.getProjectId()); row.setProjectName(allocation.getProjectName());
        row.setProjectConfirmed(true); row.setAllocationSince(now);
        FacilitySync.mergeSource(row, source, candidates, now);
        return records.insert(store, row, FacilityAssetType.facilityDevice).thenReturn(new Result(true));
      }))));
  }
}
