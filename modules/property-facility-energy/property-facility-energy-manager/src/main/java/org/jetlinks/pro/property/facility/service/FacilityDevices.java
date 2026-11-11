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

  /** Resolve paths once per visible project, without turning forbidden or missing references into a successful blank. */
  public Mono<PagerResult<DeviceEntity>> allocations(QueryParamEntity q) {
    q.setPaging(true); q.setPageSize(Math.min(100, Math.max(1, q.getPageSize())));
    return query(q).flatMap(page -> Flux.fromIterable(page.getData()).map(DeviceEntity::getProjectId)
      .filter(Objects::nonNull).distinct().concatMap(project -> directory.spaces(project).collectList())
      .collectList().map(groups -> {
        var names = new HashMap<String,String>();
        for (var group : groups) for (var space : group)
          names.put(space.getId(), Objects.toString(space.getFullName(), space.getName()));
        for (var row : page.getData()) row.setSpaceName(names.get(row.getSpaceId()));
        return page;
      }));
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
          if (!Objects.equals(row.getSpaceId(), input.getSpaceId())) row.setAllocationSince(System.currentTimeMillis());
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
        row.getVersion() == version && !row.getCandidateProjectIds().isEmpty(),
        "conflict"
      );
      return directory.project(projectId).flatMap(project ->
        (row.getProjectId() == null ? Mono.<Void>empty() : records.assertProject(row.getProjectId(), CrudAssetPermission.save))
        .then(records.assertProject(projectId, CrudAssetPermission.save)).then(
          Mono.defer(() -> {
            boolean moved = !projectId.equals(row.getProjectId());
            String previousOrganization = row.getOrganizationId();
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
            if (moved) row.setAllocationSince(System.currentTimeMillis());
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
                (moved ? records.moveOwnership(saved, FacilityAssetType.facilityDevice, previousOrganization) : Mono.<Void>empty()).then(records
                  .log(
                    saved,
                    "device",
                    "confirm-project",
                    null,
                    projectId,
                    null,
                    List.of()
                  )
                  .thenReturn(saved))
              );
          })
        )
      );
    });
  }

  /** Only project, meter category and installation space may be configured here. */
  public record Allocation(String id, long version, String projectId, String meterType, String spaceId, boolean updateSpace) {}

  /** A bounded batch is atomic; stale versions or invalid spaces roll back every selected row. */
  @Transactional
  public Mono<List<DeviceEntity>> allocate(List<Allocation> inputs) {
    require(inputs != null && !inputs.isEmpty() && inputs.size() <= 200, "too_many");
    require(inputs.stream().map(Allocation::id).distinct().count() == inputs.size(), "invalid_import");
    return Flux.fromIterable(inputs).concatMap(input -> {
      DeviceAllocationRules.type(input.meterType());
      return writable(input.id()).flatMap(old -> {
        String space = input.updateSpace() ? input.spaceId() :
          Objects.equals(old.getProjectId(), input.projectId()) ? old.getSpaceId() : null;
        Mono<Void> validSpace = space == null || space.isBlank() ? Mono.empty() : directory.space(input.projectId(), space).then();
        boolean changed = old.getAllocationSince() == null || !Objects.equals(old.getProjectId(), input.projectId()) ||
          !Objects.equals(old.getSpaceId(), space) || !Objects.equals(old.getMeterType(), input.meterType());
        return validSpace.then(confirmProject(input.id(), input.projectId(), input.version())).flatMap(row -> {
          long version = row.getVersion();
          row.setMeterType(input.meterType()); row.setSpaceId(space);
          if (changed) row.setAllocationSince(System.currentTimeMillis());
          return records.update(store, row, version, FacilityRecords.nullFields(row, "spaceId"));
        });
      });
    }).collectList();
  }

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
