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
  private final FacilityCloud cloud;

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
    return currentQuery(q, FacilityAssetType.facilityDevice, "id").flatMap(store::queryPager);
  }

  /** 当前目录取 Cloud 应用绑定与本地资产权限的交集；历史 read/writable 不受今天解绑影响。 */
  public Mono<Set<String>> currentIds() {
    return cloud.snapshot(null).flatMap(source -> {
      if (source.isEmpty()) return scope.current().thenReturn(Set.<String>of());
      var param = QueryParamEntity.newQuery().in("cloudDeviceId", source.stream()
        .map(org.jetlinks.sdk.server.device.DeviceInfo::getId).toList()).noPaging().getParam();
      return scope.query(param, FacilityAssetType.facilityDevice, "id")
        .flatMapMany(q -> store.createQuery().setParam(q).fetch()).take(20001).collectList()
        .doOnNext(rows -> require(rows.size() <= 20000, "too_many"))
        .map(rows -> rows.stream().map(DeviceEntity::getId).collect(java.util.stream.Collectors.toSet()));
    });
  }

  public Mono<QueryParamEntity> currentQuery(QueryParamEntity q, org.jetlinks.pro.assets.AssetType type, String field) {
    return currentIds().flatMap(ids -> {
      // 单独嵌套后再 AND，防止调用方 OR 条件绕过当前绑定范围；空集合显式无结果。
      var active = q.clone().toNestQuery();
      if (ids.isEmpty()) active.isNull("id"); else active.in(field, ids);
      return scope.query(active.getParam(), type, "id");
    });
  }

  public Mono<Boolean> isBound(DeviceEntity device) {
    String cloudId = device.getCloudDeviceId();
    if (cloudId == null || cloudId.isBlank()) return Mono.just(false);
    return cloud.boundDeviceIds(Set.of(cloudId)).map(ids -> ids.contains(cloudId));
  }

  Mono<QueryParamEntity> currentDeviceQuery(QueryParamEntity query, Set<String> cloudIds) {
    return cloud.boundDeviceIds(cloudIds).flatMap(ids -> {
      var current = query.clone().toNestQuery();
      if (ids.isEmpty()) current.isNull("id"); else current.in("cloudDeviceId", ids);
      return scope.query(current.getParam(), FacilityAssetType.facilityDevice, "id");
    });
  }

  /** 新配置和新采集必须仍绑定；调用前仍须通过 read/writable 校验原业务资产。 */
  public Mono<DeviceEntity> requireBound(DeviceEntity device) {
    return isBound(device).filter(Boolean.TRUE::equals).map(ignore -> device)
      .switchIfEmpty(Mono.error(new org.hswebframework.web.authorization.exception.AccessDenyException()));
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
      return validateProfile(row.getProjectId(), input).then(
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
        row.getVersion() == version,
        "conflict"
      );
      return directory.project(projectId).flatMap(project ->
        records.assertPreviousProject(row.getProjectId())
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

  /** 项目分配省略类型及空间时保留原值；旧客户端的显式字段仍按原契约校验。 */
  public record Allocation(String id, long version, String projectId, String meterType, String spaceId, boolean updateSpace) {}

  /** A bounded batch is atomic; stale versions or invalid spaces roll back every selected row. */
  @Transactional
  public Mono<List<DeviceEntity>> allocate(List<Allocation> inputs) {
    require(inputs != null && !inputs.isEmpty() && inputs.size() <= 200, "too_many");
    require(inputs.stream().map(Allocation::id).distinct().count() == inputs.size(), "invalid_import");
    return Flux.fromIterable(inputs).concatMap(input -> {
      if (input.meterType() != null) DeviceAllocationRules.type(input.meterType());
      return writable(input.id()).flatMap(old -> {
        String meterType = input.meterType() == null ? old.getMeterType() : input.meterType();
        String space = input.updateSpace() ? input.spaceId() :
          Objects.equals(old.getProjectId(), input.projectId()) ? old.getSpaceId() : null;
        Mono<Void> validSpace = space == null || space.isBlank() ? Mono.empty() : directory.space(input.projectId(), space).then();
        boolean changed = old.getAllocationSince() == null || !Objects.equals(old.getProjectId(), input.projectId()) ||
          !Objects.equals(old.getSpaceId(), space) || !Objects.equals(old.getMeterType(), meterType);
        return validSpace.then(confirmProject(input.id(), input.projectId(), input.version())).flatMap(row -> {
          long version = row.getVersion();
          row.setMeterType(meterType); row.setSpaceId(space);
          if (changed) row.setAllocationSince(System.currentTimeMillis());
          return records.update(store, row, version, FacilityRecords.nullFields(row, "spaceId"));
        });
      });
    }).collectList();
  }

  /** 能源映射保存已校验设备写权限；同事务同步类型，只在分类变化时更新归属生效时间。 */
  Mono<DeviceEntity> classify(DeviceEntity row, String type) {
    if (Objects.equals(row.getMeterType(), type)) return Mono.just(row);
    long version = row.getVersion();
    row.setMeterType(type); row.setAllocationSince(System.currentTimeMillis());
    return records.update(store, row, version);
  }

  public record ExtensionImport(
    String cloudDeviceId,
    Long version,
    Map<String, Object> values,
    Integer rowNumber
  ) {
    public ExtensionImport(String cloudDeviceId, Long version, Map<String, Object> values) {
      this(cloudDeviceId, version, values, null);
    }
  }

  /** 详情与导入共享的引用校验；负责人不能为空且必须是设备项目的有效员工。 */
  private Mono<Void> validateProfile(String projectId, DeviceEntity input) {
    require(input.getResponsibleUserId() != null && !input.getResponsibleUserId().isBlank(), "device_responsible_required");
    Mono<Void> references = directory.projectEmployee(projectId, input.getResponsibleUserId()).then();
    if (input.getSpaceId() != null && !input.getSpaceId().isBlank())
      references = references.then(directory.space(projectId, input.getSpaceId()).then());
    return references.then(files.validate(input.getAttachments(), projectId));
  }

  public record ImportCheck(int rowNumber, String deviceName, boolean valid, String message) {}

  private static void validateImportRows(List<ExtensionImport> rows) {
    require(rows != null && !rows.isEmpty() && rows.size() <= 1000, "too_many");
    require(rows.stream().allMatch(Objects::nonNull), "invalid_import");
    require(rows.stream().map(ExtensionImport::cloudDeviceId).distinct().count() == rows.size(), "invalid_import");
  }

  private Mono<DeviceEntity> prepareImport(ExtensionImport input, FacilityContext context, FacilityDeviceImportSupport support, Set<String> boundIds) {
    return Mono.defer(() -> {
      require(input.cloudDeviceId() != null && !input.cloudDeviceId().isBlank() && input.version() != null, "invalid_import");
      String id = FacilityRecords.stableId(context.tenantId(), context.applicationId(), input.cloudDeviceId());
      return writable(id).flatMap(existing -> {
        require(boundIds.contains(existing.getCloudDeviceId()), "device_unavailable");
        require(existing.getVersion().equals(input.version()), "conflict");
        return support.merge(existing, input).flatMap(merged -> validateProfile(existing.getProjectId(), merged).thenReturn(merged));
      });
    });
  }

  /** 只读校验不写台账或操作记录；错误保留 Excel 行号，提交时仍会重新校验。 */
  public Mono<List<ImportCheck>> previewExtensions(List<ExtensionImport> rows) {
    return Mono.defer(() -> {
      validateImportRows(rows);
      var support = new FacilityDeviceImportSupport(mapper, directory);
      return scope.current().zipWith(importBoundIds()).flatMap(context -> Flux.fromIterable(rows).index().concatMap(entry ->
        prepareImport(entry.getT2(), context.getT1(), support, context.getT2())
          .map(row -> new ImportCheck(importRowNumber(entry.getT2(), entry.getT1()), row.getName(), true, null))
          .onErrorResume(error -> Mono.just(new ImportCheck(importRowNumber(entry.getT2(), entry.getT1()), null, false,
            error instanceof org.hswebframework.web.authorization.exception.AccessDenyException
              ? org.hswebframework.web.i18n.LocaleUtils.resolveMessage("error.facility.import_forbidden", "error.facility.import_forbidden")
              : FacilityMeterDrafts.error(error)))))
        .collectList());
    });
  }

  private static int importRowNumber(ExtensionImport row, long index) {
    return row.rowNumber() != null && row.rowNumber() >= 2 && row.rowNumber() <= 10001 ? row.rowNumber() : (int) index + 2;
  }

  /** 整批导入处于同一事务；版本冲突、权限或引用错误均回滚本批所有更新。 */
  @Transactional
  public Mono<List<DeviceEntity>> importExtensions(List<ExtensionImport> rows) {
    return Mono.defer(() -> {
      validateImportRows(rows);
      var support = new FacilityDeviceImportSupport(mapper, directory);
      return scope.current().zipWith(importBoundIds()).flatMap(context -> Flux.fromIterable(rows)
        .concatMap(input -> prepareImport(input, context.getT1(), support, context.getT2()).flatMap(merged -> update(merged.getId(), merged)))
        .collectList());
    });
  }

  /** 一批只取一次应用设备绑定快照，避免逐行请求 SaaS；写入仍逐行检查资产和版本。 */
  private Mono<Set<String>> importBoundIds() {
    return cloud.snapshot(null).map(rows -> rows.stream().map(org.jetlinks.sdk.server.device.DeviceInfo::getId)
      .collect(java.util.stream.Collectors.toSet()));
  }

  /** One business switch backed by the original columns, including older one-sided records. */
  public static boolean workEnabled(DeviceEntity device) {
    return Boolean.TRUE.equals(device.getInspectionEnabled()) || Boolean.TRUE.equals(device.getMaintenanceEnabled());
  }

  public static void requireActive(DeviceEntity device) {
    require("active".equals(device.getSyncState()), "device_unavailable");
  }
}
