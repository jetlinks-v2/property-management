package org.jetlinks.pro.property.facility.service;

import static org.jetlinks.pro.property.facility.service.FacilityScope.require;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

/** 故障登记、处理、复核闭环；引用源先校验同一设备和项目。 */
@Service
@RequiredArgsConstructor
public class FacilityFaults {

  private final FaultStore faults;
  private final AlertStore alerts;
  private final FacilityDevices devices;
  private final FacilityDirectory directory;
  private final FacilityRecords records;
  private final FacilityScope scope;
  private final FacilityFiles files;

  @Transactional
  public Mono<FaultEntity> create(FaultEntity input) {
    return devices.writable(input.getDeviceId()).flatMap(device -> {
      require(
        input.getDescription() != null && !input.getDescription().isBlank(),
        "invalid_fault"
      );
      Mono<Void> source = Mono.empty();
      if ("alert".equals(input.getSourceType())) source = alerts
        .findById(input.getSourceId())
        .switchIfEmpty(
          Mono.error(new org.hswebframework.web.exception.NotFoundException())
        )
        .flatMap(row ->
          records
            .child(row, device)
            .doOnNext(r ->
              require(
                device.getId().equals(r.getDeviceId()),
                "project_mismatch"
              )
            )
        )
        .then();
      else require(
        input.getSourceType() == null || "manual".equals(input.getSourceType()),
        "invalid_fault"
      );
      if (input.getAssigneeId() != null) source = source
        .then(directory.employee(input.getAssigneeId()))
        .then();
      return source
        .then(files.validate(input.getAttachments(), device.getProjectId()))
        .then(scope.current())
        .flatMap(c -> {
          var row = FacilityScope.initialize(new FaultEntity(), c);
          FacilityScope.inherit(row, device);
          row.setDeviceId(device.getId());
          row.setDeviceName(device.getName());
          row.setName(input.getName());
          row.setDescription(input.getDescription());
          row.setAttachments(input.getAttachments());
          row.setSourceType(Objects.toString(input.getSourceType(), "manual"));
          row.setSourceId(input.getSourceId());
          row.setAssigneeId(input.getAssigneeId());
          row.setState("open");
          return records.insert(faults, row, FacilityAssetType.facilityFault);
        });
    });
  }

  @Transactional
  public Mono<FaultEntity> action(
    String id,
    String action,
    long version,
    String comment
  ) {
    return records
      .read(
        faults,
        id,
        FacilityAssetType.facilityFault,
        CrudAssetPermission.save
      )
      .flatMap(row ->
        scope.current().flatMap(c -> {
          require(row.getVersion() == version, "conflict");
          String from = row.getState();
          switch (action) {
            case "start" -> {
              require(
                "open".equals(from) &&
                  (row.getAssigneeId() == null ||
                    c.userId().equals(row.getAssigneeId())),
                "not_assignee"
              );
              row.setAssigneeId(c.userId());
              row.setState("processing");
            }
            case "resolve" -> {
              require(
                "processing".equals(from) &&
                  c.userId().equals(row.getAssigneeId()) &&
                  comment != null &&
                  !comment.isBlank(),
                "invalid_state"
              );
              row.setResolution(comment);
              row.setState("review");
            }
            case "approve" -> {
              require(
                "review".equals(from) &&
                  !c.userId().equals(row.getAssigneeId()),
                "invalid_review"
              );
              row.setState("closed");
              row.setCompletedTime(System.currentTimeMillis());
            }
            case "reject" -> {
              require(
                "review".equals(from) &&
                  !c.userId().equals(row.getAssigneeId()) &&
                  comment != null &&
                  !comment.isBlank(),
                "invalid_review"
              );
              row.setState("processing");
            }
            default -> require(false, "invalid_action");
          }
          return records
            .update(faults, row, version)
            .flatMap(saved ->
              records
                .log(
                  saved,
                  "fault",
                  action,
                  from,
                  saved.getState(),
                  comment,
                  List.of()
                )
                .thenReturn(saved)
            );
        })
      );
  }
}
