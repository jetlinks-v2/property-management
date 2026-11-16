package org.jetlinks.pro.property.facility.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.DeviceEntity;
import org.jetlinks.pro.property.facility.entity.TaskEntity;
import org.jetlinks.pro.property.facility.entity.FaultEntity;
import org.jetlinks.pro.property.facility.entity.MeterBillEntity;
import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class FacilityDeviceMovesTest {
  final TaskStore tasks = mock(TaskStore.class);
  final FaultStore faults = mock(FaultStore.class);
  final MeterBillStore bills = mock(MeterBillStore.class);
  final DeviceStore devices = mock(DeviceStore.class);
  final FacilityRecords records = mock(FacilityRecords.class);
  final FacilityDeviceMoves moves = new FacilityDeviceMoves(tasks, faults, bills, devices, records);

  DeviceEntity device() {
    var row = new DeviceEntity();
    row.setId("device"); row.setTenantId("tenant"); row.setApplicationId("app"); row.setProjectId("removed");
    return row;
  }

  void pending(int taskCount, int faultCount, int billCount) {
    ReactiveQuery<TaskEntity> taskQuery = mock(ReactiveQuery.class, RETURNS_SELF);
    ReactiveQuery<FaultEntity> faultQuery = mock(ReactiveQuery.class, RETURNS_SELF);
    ReactiveQuery<MeterBillEntity> billQuery = mock(ReactiveQuery.class, RETURNS_SELF);
    when(tasks.createQuery()).thenReturn(taskQuery);
    when(faults.createQuery()).thenReturn(faultQuery);
    when(bills.createQuery()).thenReturn(billQuery);
    when(taskQuery.count()).thenReturn(Mono.just(taskCount));
    when(faultQuery.count()).thenReturn(Mono.just(faultCount));
    when(billQuery.count()).thenReturn(Mono.just(billCount));
  }

  @Test void deniedDeviceCannotUseMissingProjectRecoveryEvenForSameProject() {
    when(records.read(devices, "device", FacilityAssetType.facilityDevice, CrudAssetPermission.save))
      .thenReturn(Mono.error(new AccessDenyException()));
    StepVerifier.create(moves.validate(device(), "new")).expectError(AccessDenyException.class).verify();
    StepVerifier.create(moves.validate(device(), "removed")).expectError(AccessDenyException.class).verify();
    verify(records, never()).assertPreviousProject(anyString());
    verifyNoInteractions(tasks, faults, bills);
  }

  @Test void recoveryRetainsTaskFaultAndBillGuards() {
    var row = device();
    when(records.read(devices, "device", FacilityAssetType.facilityDevice, CrudAssetPermission.save)).thenReturn(Mono.just(row));
    when(records.assertPreviousProject("removed")).thenReturn(Mono.empty());
    pending(0, 0, 0);
    StepVerifier.create(moves.validate(row, "new")).verifyComplete();
    for (int blocked = 0; blocked < 3; blocked++) {
      pending(blocked == 0 ? 1 : 0, blocked == 1 ? 1 : 0, blocked == 2 ? 1 : 0);
      StepVerifier.create(moves.validate(row, "new")).expectError(BusinessException.class).verify();
    }
  }
}
