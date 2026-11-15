package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.sdk.server.device.DeviceInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanWrapperImpl;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;

class FacilityAllocationSyncTest {
  final FacilityContext context = new FacilityContext("tenant", "application", "user", "管理员");

  DeviceInfo source(String id) { var row = new DeviceInfo(); row.setId(id); row.setName(id); return row; }
  DeviceAllocationEntity allocation(String id, String project) {
    var row = new DeviceAllocationEntity(); row.setId(id); row.setCloudDeviceId(id); row.setName(id);
    row.setProjectId(project); row.setProjectConfirmed(true); row.setVersion(1L); return row;
  }

  @Test void catalogReadsApplicationDevicesBeforeAnyBusinessProjectAssignment() {
    var cloud = mock(FacilityCloud.class); var directory = mock(FacilityDirectory.class);
    when(cloud.snapshot(null)).thenReturn(Mono.just(List.of(source("unassigned"))));
    var service = new FacilityAllocations(mock(DeviceAllocationStore.class), mock(DeviceStore.class),
      cloud, directory, mock(FacilityScope.class), mock(FacilityRecords.class), mock(FacilityDeviceMoves.class));
    StepVerifier.create(service.sources()).assertNext(rows -> {
      assertEquals(Set.of("unassigned"), FacilityAllocations.sourceIds(rows));
      assertNull(rows.get(0).id());
    }).verifyComplete();
    verifyNoInteractions(directory);
  }

  @Test void freshSourcesRemainUnassignedAndConfirmedLegacyOwnershipIsPreserved() {
    var source = source("water");
    var fresh = FacilityAllocations.imported(context, source, null);
    assertNull(fresh.getProjectId()); assertFalse(fresh.getProjectConfirmed());
    var old = new DeviceEntity(); old.setProjectId("guessed"); old.setSpaceId("room"); old.setMeterType("water");
    assertNull(FacilityAllocations.imported(context, source, old).getProjectId());
    old.setProjectId("business"); old.setProjectConfirmed(true); old.setAllocationSince(123L);
    var imported = FacilityAllocations.imported(context, source, old);
    assertEquals("business", imported.getProjectId()); assertEquals(123L, imported.getAllocationSince());
    assertEquals(fresh.getId(), imported.getId()); assertEquals("room", old.getSpaceId()); assertEquals("water", old.getMeterType());
  }

  @Test void sharedSourceDevicesAreDeduplicatedWithoutLosingDistinctDevices() {
    var rows = List.of(new FacilitySync.Source("source-a", "A", List.of(source("water"), source("gas"))),
      new FacilitySync.Source("source-b", "B", List.of(source("water"), source("power"))));
    assertEquals(Set.of("water", "gas", "power"), FacilityAllocations.sourceIds(rows));
  }

  @Test void allocationRejectsForeignTenantInvisibleSourceStaleVersionAndForbiddenProjectBeforeWriting() {
    var store = mock(DeviceAllocationStore.class); var devices = mock(DeviceStore.class);
    var directory = mock(FacilityDirectory.class); var scope = mock(FacilityScope.class); var records = mock(FacilityRecords.class);
    var service = spy(new FacilityAllocations(store,devices,mock(FacilityCloud.class),directory,scope,records,mock(FacilityDeviceMoves.class)));
    when(scope.current()).thenReturn(Mono.just(context));
    var row = allocation("source","old"); row.setTenantId(context.tenantId()); row.setApplicationId(context.applicationId());
    when(store.findById("source")).thenReturn(Mono.just(row));
    var snapshots = List.of(new FacilitySync.Source("cloud","Cloud",List.of(source("source"))));
    doReturn(Mono.just(snapshots)).when(service).sources();
    var input = List.of(new FacilityDevices.Allocation("source",1L,"new",null,null,false));
    row.setTenantId("foreign");
    StepVerifier.create(service.save(input)).expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
    row.setTenantId(context.tenantId());
    doReturn(Mono.just(List.of())).when(service).sources();
    StepVerifier.create(service.save(input)).expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
    doReturn(Mono.just(snapshots)).when(service).sources(); row.setVersion(2L);
    StepVerifier.create(service.save(input)).expectError(org.hswebframework.web.exception.BusinessException.class).verify();
    row.setVersion(1L);
    var target = new org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo(); target.setId("new");
    when(directory.project("new")).thenReturn(Mono.just(target));
    when(records.assertProject(anyString(),any())).thenReturn(Mono.error(new org.hswebframework.web.authorization.exception.AccessDenyException()));
    when(devices.findById("source")).thenReturn(Mono.empty());
    StepVerifier.create(service.save(input)).expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
    verify(records,never()).update(any(),any(),anyLong());
    verify(store,never()).createUpdate();
  }

  @Test void syncUsesSavedBusinessProjectEvenWhenCloudOrganizationDiffersAndDoesNotImportUnassignedDevices() {
    var allocations = mock(FacilityAllocations.class);
    var devices = mock(FacilitySyncDevice.class);
    var jobs = mock(JobStore.class, RETURNS_DEEP_STUBS);
    var job = new JobEntity(); job.setId("job"); job.setProjectId("business"); job.setLastStartTime(42L);
    var update = jobs.createUpdate();
    when(update.set(anyString(), any())).thenAnswer(call -> { new BeanWrapperImpl(job).setPropertyValue(call.getArgument(0), call.getArgument(1)); return update; });
    when(update.setNull(anyString())).thenReturn(update);
    when(update.where(anyString(), any())).thenReturn(update);
    when(update.and(anyString(), any())).thenReturn(update);
    when(update.execute()).thenReturn(Mono.just(1)); when(jobs.findById("job")).thenReturn(Mono.just(job));
    var water = allocation("water", "business");
    when(allocations.configured("business")).thenReturn(Flux.just(water));
    var source = source("water");
    when(devices.apply(water, source, List.of("cloud-source"), 42L)).thenReturn(Mono.just(new FacilitySyncDevice.Result(true)));
    var writer = new FacilitySyncWriter(allocations, devices, jobs);
    var snapshot = List.of(new FacilitySync.Source("cloud-source", "来源", List.of(source, source("unassigned"))));
    StepVerifier.create(writer.apply(job, snapshot, 42)).assertNext(result -> {
      assertEquals(1, result.getInserted()); assertEquals(0, result.getUpdated()); assertEquals(0, result.getFailed());
      assertEquals("success", result.getState());
    }).verifyComplete();
    verify(devices).apply(water, source, List.of("cloud-source"), 42L); verifyNoMoreInteractions(devices);
  }

  @Test void inaccessibleSourceIsReportedAndDoesNotPreventOtherDevicesFromSyncing() {
    var allocations = mock(FacilityAllocations.class); var devices = mock(FacilitySyncDevice.class);
    var jobs = mock(JobStore.class, RETURNS_DEEP_STUBS);
    var job = new JobEntity(); job.setId("job"); job.setProjectId("business");
    var update = jobs.createUpdate();
    when(update.set(anyString(), any())).thenAnswer(call -> { new BeanWrapperImpl(job).setPropertyValue(call.getArgument(0), call.getArgument(1)); return update; });
    when(update.setNull(anyString())).thenReturn(update); when(update.where(anyString(), any())).thenReturn(update);
    when(update.and(anyString(), any())).thenReturn(update); when(update.execute()).thenReturn(Mono.just(1));
    when(jobs.findById("job")).thenReturn(Mono.just(job));
    var water = allocation("water", "business"); var missing = allocation("missing", "business");
    when(allocations.configured("business")).thenReturn(Flux.just(missing, water));
    var source = source("water");
    when(devices.apply(water, source, List.of("source"), 42L)).thenReturn(Mono.just(new FacilitySyncDevice.Result(false)));
    StepVerifier.create(new FacilitySyncWriter(allocations, devices, jobs).apply(job,
      List.of(new FacilitySync.Source("source", "来源", List.of(source))), 42)).assertNext(result -> {
        assertEquals(1, result.getUpdated()); assertEquals(1, result.getFailed()); assertEquals(1, result.getProcessed());
        assertEquals("failed", result.getState()); assertTrue(result.getFailureReason().contains("missing"));
      }).verifyComplete();
    verify(devices).apply(water, source, List.of("source"), 42L); verifyNoMoreInteractions(devices);
  }
}
