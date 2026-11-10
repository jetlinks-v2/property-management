package org.jetlinks.pro.property.facility.service;

import static org.jetlinks.pro.property.facility.service.FacilityScope.require;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.*;

/** 设施台账只修改本地扩展；Cloud身份与归属不接受导入或保存接口覆盖。 */
@Service
@RequiredArgsConstructor
public class FacilityDevices {

  private final com.fasterxml.jackson.databind.ObjectMapper mapper;
  private final BindingStore bindings;
  private final MappingStore mappings;
  private final FacilityFiles files;
  private final DeviceStore store;
  private final FacilityScope scope;
  private final FacilityRecords records;
  private final FacilityDirectory directory;

  public Mono<DeviceEntity> read(String id) {
    return records.read(
      store,
      id,
      FacilityAssetType.facilityDevice,
      CrudAssetPermission.read
    );
  }

  public Mono<DeviceEntity> writable(String id) {
    return records.read(
      store,
      id,
      FacilityAssetType.facilityDevice,
      CrudAssetPermission.save
    );
  }

  public Mono<PagerResult<DeviceEntity>> query(QueryParamEntity q) {
    return scope
      .query(q, FacilityAssetType.facilityDevice, "id")
      .flatMap(store::queryPager);
  }

  @Transactional
  public Mono<DeviceEntity> update(String id, DeviceEntity input) {
    return writable(id).flatMap(row -> {
      require(Objects.equals(row.getVersion(), input.getVersion()), "conflict");
      Mono<Void> references = files.validate(input.getAttachments(), row.getProjectId());
      if (
        input.getSpaceId() != null && !input.getSpaceId().isBlank()
      ) references = references.then(
        directory.space(row.getProjectId(), input.getSpaceId()).then()
      );
      if (
        input.getResponsibleUserId() != null &&
        !input.getResponsibleUserId().isBlank()
      ) references = references.then(
        directory.employee(input.getResponsibleUserId()).then()
      );
      return references.then(
        Mono.defer(() -> {
          long version = row.getVersion();
          row.setName(input.getName());
          row.setSystemName(input.getSystemName());
          row.setCategoryName(input.getCategoryName());
          row.setSpecification(input.getSpecification());
          row.setPower(input.getPower());
          row.setManufacturer(input.getManufacturer());
          row.setPurchasePrice(input.getPurchasePrice());
          row.setPurchaseDate(input.getPurchaseDate());
          row.setStartDate(input.getStartDate());
          row.setWarrantyDate(input.getWarrantyDate());
          row.setMaintainer(input.getMaintainer());
          row.setMaintainerUserId(input.getMaintainerUserId());
          row.setResponsibleUserId(input.getResponsibleUserId());
          row.setSpaceId(input.getSpaceId());
          row.setLocation(input.getLocation());
          row.setFloor(input.getFloor());
          row.setRemark(input.getRemark());
          row.setAttachments(input.getAttachments());
          // Preserve legacy one-sided enablement; future saves keep both columns in sync.
          row.setInspectionEnabled(workEnabled(input));
          row.setMaintenanceEnabled(workEnabled(input));
          row.setEnergyEnabled(input.getEnergyEnabled());
          return records
            .update(
              store,
              row,
              version,
              FacilityRecords.nullFields(
                row,
                "name",
                "systemName",
                "categoryName",
                "specification",
                "power",
                "manufacturer",
                "purchasePrice",
                "purchaseDate",
                "startDate",
                "warrantyDate",
                "maintainer",
                "maintainerUserId",
                "responsibleUserId",
                "spaceId",
                "location",
                "floor",
                "remark"
              )
            )
            .flatMap(saved ->
              records
                .log(saved, "device", "update", null, null, null, List.of())
                .thenReturn(saved)
            );
        })
      );
    });
  }

  @Transactional
  public Mono<DeviceEntity> confirmProject(
    String id,
    String projectId,
    long version
  ) {
    return writable(id).flatMap(row -> {
      require(
        row.getVersion() == version &&
          row.getCandidateProjectIds().contains(projectId),
        "conflict"
      );
      return directory.project(projectId).flatMap(project ->
        records.assertProject(projectId, CrudAssetPermission.save).then(
          Mono.defer(() -> {
            boolean moved = !projectId.equals(row.getProjectId());
            Mono<Void> move = Mono.empty();
            if (moved) {
              long end = System.currentTimeMillis();
              move = bindings
                .createUpdate()
                .set("endTime", end)
                .set("endReason", "device-project-moved")
                .where("tenantId", row.getTenantId())
                .and("applicationId", row.getApplicationId())
                .and("deviceId", row.getId())
                .isNull("endTime")
                .execute()
                .then(
                  mappings
                    .createUpdate()
                    .set("enabled", false)
                    .where("tenantId", row.getTenantId())
                    .and("applicationId", row.getApplicationId())
                    .and("deviceId", row.getId())
                    .execute()
                )
                .then();
              row.setResponsibleUserId(null);
              row.setMaintainerUserId(null);
              row.setInspectionEnabled(false);
              row.setMaintenanceEnabled(false);
              row.setEnergyEnabled(false);
            }
            row.setProjectConfirmed(true);
            row.setProjectId(projectId);
            row.setOrganizationId(projectId);
            row.setProjectName(project.getName());
            // Project ownership does not override activation owned by the Cloud source.
            row.setSyncState("notActive".equals(row.getSourceState()) ? "inactive" : "active");
            row.setPendingProjectId(null);
            if (moved) row.setSpaceId(null);
            return move
              .then(
                records.update(
                  store,
                  row,
                  version,
                  moved
                    ? new String[] {
                        "pendingProjectId",
                        "spaceId",
                        "responsibleUserId",
                        "maintainerUserId",
                      }
                    : new String[] { "pendingProjectId" }
                )
              )
              .flatMap(saved ->
                records
                  .log(
                    saved,
                    "device",
                    "confirm-project",
                    null,
                    projectId,
                    null,
                    List.of()
                  )
                  .thenReturn(saved)
              );
          })
        )
      );
    });
  }

  /** Partial extension import is atomic; source identity and lifecycle flags cannot be mass-assigned. */
  public record ExtensionImport(
    String cloudDeviceId,
    Long version,
    Map<String, Object> values
  ) {}

  private static final Set<String> IMPORT_FIELDS = Set.of(
    "name",
    "systemName",
    "categoryName",
    "specification",
    "power",
    "manufacturer",
    "purchasePrice",
    "purchaseDate",
    "startDate",
    "warrantyDate",
    "maintainer",
    "responsibleUserId",
    "spaceId",
    "location",
    "floor",
    "remark"
  );

  @Transactional
  public Mono<List<DeviceEntity>> importExtensions(List<ExtensionImport> rows) {
    require(!rows.isEmpty() && rows.size() <= 1000, "too_many");
    require(
      rows.stream().map(ExtensionImport::cloudDeviceId).distinct().count() ==
        rows.size(),
      "invalid_import"
    );
    return scope.current().flatMap(c ->
      Flux.fromIterable(rows)
        .concatMap(input -> {
          require(
            input.cloudDeviceId() != null &&
              !input.cloudDeviceId().isBlank() &&
              input.version() != null &&
              input.values() != null &&
              IMPORT_FIELDS.containsAll(input.values().keySet()),
            "invalid_import"
          );
          String id = FacilityRecords.stableId(
            c.tenantId(),
            c.applicationId(),
            input.cloudDeviceId()
          );
          return writable(id).flatMap(existing -> {
            require(existing.getVersion().equals(input.version()), "conflict");
            var merged = mapper.convertValue(existing, DeviceEntity.class);
            try {
              mapper.updateValue(merged, input.values());
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
              return Mono.error(
                new org.hswebframework.web.exception.BusinessException(
                  "error.facility.invalid_import",
                  400
                )
              );
            }
            return update(id, merged);
          });
        })
        .collectList()
    );
  }

  /** One business switch backed by the original columns, including older one-sided records. */
  public static boolean workEnabled(DeviceEntity device) {
    return Boolean.TRUE.equals(device.getInspectionEnabled()) || Boolean.TRUE.equals(device.getMaintenanceEnabled());
  }

  public static void requireActive(DeviceEntity device) {
    require("active".equals(device.getSyncState()), "device_unavailable");
  }
}
