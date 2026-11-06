package org.jetlinks.pro.property.facility.service;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.util.List;
import org.jetlinks.pro.assets.AssetBindManager;
import org.jetlinks.pro.assets.AssetBindRequest;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;
import org.jetlinks.pro.property.facility.entity.TaskEntity;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class FacilityExternalTaskTest {
    private final AssetBindManager bindings = mock(AssetBindManager.class);
    private final FacilityPlans plans = new FacilityPlans(null, null, null, null, null, null, null, null, bindings);

    @Test
    void sharesOnlyTheValidatedDoubleTaskAndPropagatesBindingDenial() {
        var task = new TaskEntity();
        task.setId("task");
        task.setMode("double");
        task.setExternalOrganizationId("external");
        when(bindings.bindAssets(any())).thenAnswer(call -> Flux.from(call.<org.reactivestreams.Publisher<AssetBindRequest>>getArgument(0))
                .doOnNext(request -> {
                    assertEquals("org", request.getTargetType());
                    assertEquals("external", request.getTargetId());
                    assertEquals("facilityTask", request.getAssetType());
                    assertEquals(List.of("task"), request.getAssetIdList());
                    assertEquals(List.of("read", "save"), request.getPermission());
                    assertFalse(request.isAllPermission());
                }).then());
        StepVerifier.create(plans.bindExternalTask(task)).expectNext(task).verifyComplete();
        verify(bindings).bindAssets(any());
        doReturn(Mono.error(new IllegalStateException("binding unavailable"))).when(bindings).bindAssets(any());
        StepVerifier.create(plans.bindExternalTask(task)).expectError(IllegalStateException.class).verify();
    }

    @Test
    void singleTaskDoesNotGrantExternalAccess() {
        var task = new TaskEntity();
        task.setMode("single");
        StepVerifier.create(plans.bindExternalTask(task)).expectNext(task).verifyComplete();
        verifyNoInteractions(bindings);
    }
}
