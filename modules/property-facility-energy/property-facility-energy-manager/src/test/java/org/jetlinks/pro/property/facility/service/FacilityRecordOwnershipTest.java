package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.TaskEntity;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;

/** 绑定参数契约；真实组织允许／拒绝对由远程项目工单流程覆盖。 */
class FacilityRecordOwnershipTest {
  @Test
  void bindsValidatedProjectAndApplicationInsteadOfCreatorDefaults() {
    var bindings = mock(AssetBindManager.class);
    var records = new FacilityRecords(null, null, bindings);
    var task = new TaskEntity();
    task.setId("task");
    task.setOrganizationId("selected-project");
    task.setApplicationId("selected-app");
    var requests = new ArrayList<AssetBindRequest>();
    when(bindings.bindAssets(any())).thenAnswer(call ->
      Flux.from(call.<org.reactivestreams.Publisher<AssetBindRequest>>getArgument(0))
        .doOnNext(requests::add).then());
    StepVerifier.create(records.bindOwnership(task, FacilityAssetType.facilityTask)).verifyComplete();
    assertEquals(List.of("org", "business_application"), requests.stream().map(AssetBindRequest::getTargetType).toList());
    assertEquals(List.of("selected-project", "selected-app"), requests.stream().map(AssetBindRequest::getTargetId).toList());
    requests.forEach(request -> {
      assertEquals("facilityTask", request.getAssetType());
      assertEquals(List.of("task"), request.getAssetIdList());
      assertTrue(request.isAllPermission());
    });
    doReturn(Mono.error(new IllegalStateException("binding failed"))).when(bindings).bindAssets(any());
    StepVerifier.create(records.bindOwnership(task, FacilityAssetType.facilityTask))
      .expectError(IllegalStateException.class).verify();
  }
}
