package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class FacilityReplacementsTest {
  private final MappingStore mappings = mock(MappingStore.class);
  private final ReplacementStore store = mock(ReplacementStore.class);
  private final FacilityDevices devices = mock(FacilityDevices.class);
  private final FacilityRecords records = mock(FacilityRecords.class);
  private final FacilityScope scope = mock(FacilityScope.class);
  private final FacilityReplacements service = new FacilityReplacements(null, mappings, null, null, store, devices, records, scope);
  private final MappingEntity oldMapping = mapping("electricity"), nextMapping = mapping("electricity");

  @BeforeEach
  void setup() {
    for (String id : new String[]{"old", "next"}) {
      var device = new DeviceEntity(); device.setId(id); device.setTenantId("tenant");
      device.setApplicationId("app"); device.setProjectId("project"); device.setEnergyEnabled(true); device.setSyncState("active");
      when(devices.writable(id)).thenReturn(Mono.just(device));
      when(devices.requireBound(device)).thenReturn(Mono.just(device));
      String mappingId = FacilityRecords.stableId("tenant", "app", id, "project", "mapping");
      when(records.read(mappings, mappingId, FacilityAssetType.facilityMapping, CrudAssetPermission.read))
        .thenReturn(Mono.just(id.equals("old") ? oldMapping : nextMapping));
    }
    when(scope.current()).thenReturn(Mono.just(new FacilityContext("tenant", "app", "executor", "Executor")));
    when(store.insert(any(ReplacementEntity.class))).thenReturn(Mono.just(1));
  }

  private static MappingEntity mapping(String energy) {
    var row = new MappingEntity(); row.setEnergyType(energy); row.setEnabled(true); return row;
  }

  private ReplacementEntity input() {
    var row = new ReplacementEntity(); row.setOldDeviceId("old"); row.setNewDeviceId("next");
    row.setEffectiveTime(System.currentTimeMillis() - 1000); row.setFinalReading(BigDecimal.TEN);
    row.setInitialReading(BigDecimal.ZERO); row.setReason("换表复核"); return row;
  }

  @Test
  void rejectsNegativeAndMissingReadingsBeforePersistence() {
    for (int scenario = 0; scenario < 4; scenario++) {
      var row = input();
      if (scenario == 0) row.setFinalReading(BigDecimal.ONE.negate());
      if (scenario == 1) row.setInitialReading(BigDecimal.ONE.negate());
      if (scenario == 2) row.setFinalReading(null);
      if (scenario == 3) row.setInitialReading(null);
      StepVerifier.create(Mono.defer(() -> service.create(row))).expectError(BusinessException.class).verify();
    }
    verify(store, never()).insert(any(ReplacementEntity.class));
  }

  @Test
  void rejectsDifferentEnergyAndDisabledMapping() {
    nextMapping.setEnergyType("gas");
    StepVerifier.create(service.create(input())).expectError(BusinessException.class).verify();
    nextMapping.setEnergyType("electricity"); nextMapping.setEnabled(false);
    StepVerifier.create(service.create(input())).expectError(BusinessException.class).verify();
    verify(store, never()).insert(any(ReplacementEntity.class));
  }

  @Test
  void acceptsSameEnergyAndZeroInitialReadingWithoutChangingOwnership() {
    StepVerifier.create(service.create(input())).assertNext(row -> {
      assertEquals("pending", row.getState()); assertEquals("project", row.getProjectId());
      assertEquals("executor", row.getCreatorId()); assertEquals(BigDecimal.ZERO, row.getInitialReading());
    }).verifyComplete();
  }
}
