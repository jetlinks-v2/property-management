package org.jetlinks.pro.property.facility.service;

import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.DeviceEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** 项目迁移只处理当前归属；历史任务、读数与应收快照永不随迁移重写。 */
@Service
@RequiredArgsConstructor
public class FacilityDeviceMoves {
  private final TaskStore tasks;
  private final FaultStore faults;
  private final MeterBillStore bills;
  private final DeviceStore devices;
  private final FacilityRecords records;

  public Mono<Void> validate(DeviceEntity device, String projectId) {
    if (Objects.equals(device.getProjectId(), projectId)) return Mono.empty();
    return (device.getProjectId() == null ? Mono.<Void>empty() : records.assertProject(device.getProjectId(), CrudAssetPermission.save))
      .then(records.read(devices, device.getId(), FacilityAssetType.facilityDevice, CrudAssetPermission.save))
      .then(Mono.zip(
        tasks.createQuery().where("tenantId", device.getTenantId()).and("applicationId", device.getApplicationId())
          .and("deleted", false).and("deviceId", device.getId()).notIn("state", List.of("completed", "closed", "cancelled")).count(),
        faults.createQuery().where("tenantId", device.getTenantId()).and("applicationId", device.getApplicationId())
          .and("deleted", false).and("deviceId", device.getId()).notIn("state", List.of("closed", "cancelled")).count(),
        bills.createQuery().where("tenantId", device.getTenantId()).and("applicationId", device.getApplicationId())
          .and("deleted", false).and("deviceId", device.getId()).not("state", "approved").count()
      )).doOnNext(counts -> FacilityScope.require(counts.getT1() == 0 && counts.getT2() == 0 && counts.getT3() == 0, "allocation_busy"))
      .then();
  }
}
