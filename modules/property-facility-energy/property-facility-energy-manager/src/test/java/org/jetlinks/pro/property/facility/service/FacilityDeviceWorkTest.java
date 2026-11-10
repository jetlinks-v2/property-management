package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class FacilityDeviceWorkTest {
  @Test
  void deviceSaveUnifiesLegacyFlagsAndExplicitDisableClearsBoth() {
    var records = mock(FacilityRecords.class);
    var files = mock(FacilityFiles.class);
    var store = mock(DeviceStore.class);
    when(records.log(any(), anyString(), anyString(), nullable(String.class), nullable(String.class), nullable(String.class), anyList()))
      .thenReturn(Mono.empty());
    var manager = new FacilityDevices(new ObjectMapper(), null, null, files, store, null, records, null);
    for (boolean inspection : List.of(false, true)) for (boolean maintenance : List.of(false, true)) {
      var row = new DeviceEntity();
      row.setId("device"); row.setVersion(5L); row.setProjectId("project");
      row.setInspectionEnabled(true); row.setMaintenanceEnabled(true);
      var input = new DeviceEntity();
      input.setVersion(5L); input.setInspectionEnabled(inspection); input.setMaintenanceEnabled(maintenance);
      when(records.read(store, "device", FacilityAssetType.facilityDevice, CrudAssetPermission.save)).thenReturn(Mono.just(row));
      when(files.validate(anyList(), eq("project"))).thenReturn(Mono.empty());
      when(records.update(eq(store), any(DeviceEntity.class), eq(5L), any(String[].class)))
        .thenAnswer(call -> Mono.just(call.getArgument(1)));
      StepVerifier.create(manager.update("device", input)).assertNext(saved -> {
        assertEquals(inspection || maintenance, saved.getInspectionEnabled());
        assertEquals(inspection || maintenance, saved.getMaintenanceEnabled());
      }).verifyComplete();
    }
  }

  @Test
  void groupRejectsDisabledAndForeignProjectDevicesAndAcceptsEitherLegacyFlag() {
    var records = mock(FacilityRecords.class);
    var directory = mock(FacilityDirectory.class);
    var devices = mock(FacilityDevices.class);
    var scope = mock(FacilityScope.class);
    var groups = mock(GroupStore.class);
    var manager = new FacilityConfiguration(null, groups, records, scope, directory, devices, null);
    when(directory.project("project")).thenReturn(Mono.just(new PropertyOrganizationInfo()));
    when(records.assertProject("project", CrudAssetPermission.save)).thenReturn(Mono.empty());
    when(scope.current()).thenReturn(Mono.just(new FacilityContext("tenant", "app", "user", "User")));
    when(records.insert(eq(groups), any(GroupEntity.class), eq(FacilityAssetType.facilityGroup)))
      .thenAnswer(call -> Mono.just(call.getArgument(1)));
    var group = new GroupEntity();
    group.setName("设备组"); group.setProjectId("project"); group.setDeviceIds(List.of("device"));
    var device = new DeviceEntity(); device.setProjectId("project");
    when(devices.read("device")).thenReturn(Mono.just(device));
    StepVerifier.create(manager.group(group)).expectErrorMatches(error -> error instanceof BusinessException &&
      error.getMessage().contains("device_work_disabled")).verify();
    verify(records, never()).insert(any(), any(), any());
    device.setInspectionEnabled(true);
    StepVerifier.create(manager.group(group)).expectNextCount(1).verifyComplete();
    device.setInspectionEnabled(false); device.setMaintenanceEnabled(true);
    StepVerifier.create(manager.group(group)).expectNextCount(1).verifyComplete();
    device.setProjectId("other-project");
    StepVerifier.create(manager.group(group)).expectErrorMatches(error -> error instanceof BusinessException &&
      error.getMessage().contains("project_mismatch")).verify();
  }
}
