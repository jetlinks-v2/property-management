package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.facility.model.CloudAlarmRecord;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class FacilityAlarmReliefTest {
  final AlertStore alerts = mock(AlertStore.class);
  final FacilityDevices devices = mock(FacilityDevices.class);
  final FacilityRecords records = mock(FacilityRecords.class);
  final FacilityCloudAlarms cloud = mock(FacilityCloudAlarms.class);
  final FacilitySaasAlarmWriter writer = mock(FacilitySaasAlarmWriter.class);
  final AlertEntity row = new AlertEntity();
  final DeviceEntity device = new DeviceEntity();

  FacilityAlarmReliefTest() {
    row.setId("local"); row.setDeviceId("meter"); row.setSource("saas"); row.setSourceConfigId("rule");
    row.setSourceRecordId("platform-record"); row.setFirstTime(1000L); row.setVersion(4L);
    row.setProcessingState("closed"); row.setComment("kept");
    device.setId("meter"); device.setCloudDeviceId("cloud-meter");
    when(alerts.findById("local")).thenReturn(Mono.just(row));
    when(devices.writable("meter")).thenReturn(Mono.just(device));
    when(devices.requireBound(device)).thenReturn(Mono.just(device));
    when(records.child(row, device)).thenReturn(Mono.just(row));
    when(writer.accept(eq(device), any())).thenReturn(Mono.empty());
    when(writer.acceptRelief(eq(device), any(), eq(row), anyString())).thenReturn(Mono.empty());
  }

  FacilityAlarmRelief service() { return new FacilityAlarmRelief(alerts, devices, records, cloud, writer); }
  CloudAlarmRecord source(String state) {
    var source = new CloudAlarmRecord(); source.setId("platform-record"); source.setAlarmConfigId("rule");
    source.setTargetType("device"); source.setTargetId("cloud-meter"); source.setState(state);
    source.setAlarmTime(1000L); source.setLastAlarmTime(1500L); return source;
  }

  @Test void sendsPlatformCommandThenVerifiesStateAndKeepsLocalHandlingSeparate() {
    var active = source("warning"); var normal = source("normal"); normal.setHandleTime(2000L);
    when(cloud.records("cloud-meter")).thenReturn(Mono.just(List.of(active)), Mono.just(List.of(normal)));
    when(cloud.relieve(active, "valve checked")).thenReturn(Mono.empty());
    StepVerifier.create(service().relieve("local", 4, " valve checked ")).expectNext(row).verifyComplete();
    var order = inOrder(cloud, writer);
    order.verify(cloud).records("cloud-meter"); order.verify(cloud).relieve(active, "valve checked");
    order.verify(cloud).records("cloud-meter"); order.verify(writer).acceptRelief(device, normal, row, "valve checked");
    assertEquals("closed", row.getProcessingState()); assertEquals("kept", row.getComment());
  }

  @Test void platformFailureAndUnconfirmedStateNeverWriteLocalSuccess() {
    var active = source("warning"); when(cloud.records(anyString())).thenReturn(Mono.just(List.of(active)));
    when(cloud.relieve(any(), anyString())).thenReturn(Mono.error(new IllegalStateException("SaaS unavailable")));
    StepVerifier.create(service().relieve("local", 4, "reason")).expectError(IllegalStateException.class).verify();
    verifyNoInteractions(writer);
    when(cloud.relieve(any(), anyString())).thenReturn(Mono.empty());
    StepVerifier.create(service().relieve("local", 4, "reason")).expectError().verify();
    verifyNoInteractions(writer);
  }

  @Test void alreadyClearedOnlyReconcilesAndNewRoundMustBeRefreshed() {
    var normal = source("normal"); when(cloud.records(anyString())).thenReturn(Mono.just(List.of(normal)));
    StepVerifier.create(service().relieve("local", 4, "reason")).expectNext(row).verifyComplete();
    verify(cloud, never()).relieve(any(), anyString()); verify(writer).accept(device, normal);
    var newer = source("warning"); newer.setAlarmTime(5000L);
    when(cloud.records(anyString())).thenReturn(Mono.just(List.of(newer)));
    StepVerifier.create(service().relieve("local", 4, "reason")).expectError().verify();
    verify(cloud, never()).relieve(any(), anyString());
  }

  @Test void unauthorizedAndUnboundDevicesAreRejectedBeforeAnyPlatformAccess() {
    when(devices.writable("meter")).thenReturn(Mono.error(new org.hswebframework.web.authorization.exception.AccessDenyException()));
    StepVerifier.create(service().relieve("local", 4, "reason")).expectError().verify(); verifyNoInteractions(cloud);
    when(devices.writable("meter")).thenReturn(Mono.just(device));
    when(devices.requireBound(device)).thenReturn(Mono.error(new org.hswebframework.web.authorization.exception.AccessDenyException()));
    StepVerifier.create(service().relieve("local", 4, "reason")).expectError().verify(); verifyNoInteractions(cloud);
  }

  @Test void invalidReasonVersionLocalAlarmAndAggregateTargetCannotMutatePlatform() {
    StepVerifier.create(service().relieve("local", 4, " ")).expectError().verify();
    StepVerifier.create(service().relieve("local", 3, "reason")).expectError().verify();
    row.setSource("local"); StepVerifier.create(service().relieve("local", 4, "reason")).expectError().verify();
    row.setSource("saas"); var source = source("warning"); source.setTargetType("product");
    when(cloud.records(anyString())).thenReturn(Mono.just(List.of(source)));
    StepVerifier.create(service().relieve("local", 4, "reason")).expectError().verify();
    verify(cloud, never()).relieve(any(), anyString()); verifyNoInteractions(writer);
  }
}
