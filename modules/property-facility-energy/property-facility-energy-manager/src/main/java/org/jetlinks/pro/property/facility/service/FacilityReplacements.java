package org.jetlinks.pro.property.facility.service;

import static org.jetlinks.pro.property.facility.service.FacilityScope.require;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.jetlinks.pro.property.facility.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

/** 换表或归零必须审核后形成新计量段，不把跨段差值计入用量。 */
@Service
@RequiredArgsConstructor
public class FacilityReplacements {

  private final ReadingStore readings;
  private final MappingStore mappings;
  private final DeviceStore deviceStore;
  private final BindingStore bindings;
  private final ReplacementStore store;
  private final FacilityDevices devices;
  private final FacilityRecords records;
  private final FacilityScope scope;

  @Transactional
  public Mono<ReplacementEntity> create(ReplacementEntity input) {
    require(
      input.getEffectiveTime() != null &&
        input.getEffectiveTime() <= System.currentTimeMillis() &&
        input.getReason() != null &&
        !input.getReason().isBlank(),
      "invalid_reading"
    );
    return devices.writable(input.getOldDeviceId()).flatMap(old ->
      devices.writable(input.getNewDeviceId()).flatMap(next -> {
        require(
          old.getProjectId().equals(next.getProjectId()),
          "project_mismatch"
        );
        return scope.current().flatMap(c -> {
          var row = FacilityScope.initialize(new ReplacementEntity(), c);
          FacilityScope.inherit(row, old);
          row.setOldDeviceId(old.getId());
          row.setNewDeviceId(next.getId());
          row.setEffectiveTime(input.getEffectiveTime());
          row.setFinalReading(input.getFinalReading());
          row.setInitialReading(input.getInitialReading());
          row.setReason(input.getReason());
          row.setState("pending");
          return store.insert(row).thenReturn(row);
        });
      })
    );
  }

  @Transactional
  public Mono<ReplacementEntity> review(
    String id,
    long version,
    boolean approve
  ) {
    return store
      .findById(id)
      .switchIfEmpty(
        Mono.error(new org.hswebframework.web.exception.NotFoundException())
      )
      .flatMap(row ->
        devices
          .writable(row.getOldDeviceId())
          .flatMap(parent -> records.child(row, parent))
          .then(devices.writable(row.getNewDeviceId()))
          .then(scope.current())
          .flatMap(c -> {
            require(
              row.getVersion() == version &&
                "pending".equals(row.getState()) &&
                !row.getCreatorId().equals(c.userId()),
              "invalid_review"
            );
            row.setState(approve ? "approved" : "rejected");
            row.setReviewerId(c.userId());
            Mono<Void> boundary = Mono.empty();
            if (approve) {
              boundary = store
                .createQuery()
                .where("tenantId", row.getTenantId())
                .and("applicationId", row.getApplicationId())
                .and("newDeviceId", row.getNewDeviceId())
                .and("state", "approved")
                .gte("effectiveTime", row.getEffectiveTime())
                .count()
                .doOnNext(n -> require(n == 0, "invalid_period"))
                .then(
                  readings
                    .createUpdate()
                    .set("meterEpoch", row.getEffectiveTime())
                    .where("tenantId", row.getTenantId())
                    .and("applicationId", row.getApplicationId())
                    .and("deviceId", row.getNewDeviceId())
                    .gte("observedTime", row.getEffectiveTime())
                    .execute()
                )
                .then();
              if (!row.getOldDeviceId().equals(row.getNewDeviceId())) boundary =
                boundary
                  .then(
                    deviceStore
                      .createUpdate()
                      .set("energyEnabled", false)
                      .where("id", row.getOldDeviceId())
                      .execute()
                  )
                  .then(
                    mappings
                      .createUpdate()
                      .set("enabled", false)
                      .where("deviceId", row.getOldDeviceId())
                      .and("tenantId", row.getTenantId())
                      .and("applicationId", row.getApplicationId())
                      .execute()
                  )
                  .then(
                    bindings
                      .createUpdate()
                      .set("endTime", System.currentTimeMillis())
                      .set("endReason", "meter-replaced")
                      .where("deviceId", row.getOldDeviceId())
                      .and("tenantId", row.getTenantId())
                      .and("applicationId", row.getApplicationId())
                      .isNull("endTime")
                      .execute()
                  )
                  .then();
            }
            return boundary
              .then(records.update(store, row, version))
              .flatMap(saved ->
                records
                  .log(
                    saved,
                    "replacement",
                    approve ? "approve" : "reject",
                    "pending",
                    saved.getState(),
                    saved.getReason(),
                    List.of()
                  )
                  .thenReturn(saved)
              );
          })
      );
  }
}
