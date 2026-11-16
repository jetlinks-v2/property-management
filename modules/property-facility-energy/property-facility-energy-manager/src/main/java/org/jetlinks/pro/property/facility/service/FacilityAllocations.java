package org.jetlinks.pro.property.facility.service;

import static org.jetlinks.pro.property.facility.service.FacilityScope.require;

import java.time.Duration;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.exception.NotFoundException;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.*;

/** 来源设备由 Cloud SDK 裁剪，项目写入由目录与 AssetsHolder 校验；目录本身不是可执行业务的资产。 */
@Service
@RequiredArgsConstructor
public class FacilityAllocations {
  private final DeviceAllocationStore store;
  private final DeviceStore devices;
  private final FacilityCloud cloud;
  private final FacilityDirectory directory;
  private final FacilityScope scope;
  private final FacilityRecords records;
  private final FacilityDeviceMoves moves;

  public Mono<List<FacilitySync.Source>> sources() {
    // 应用授权的来源与业务目标项目分离；null 表示来源快照不声明项目归属。
    return cloud.snapshot(null)
      .map(rows -> List.of(new FacilitySync.Source(null, null, rows)))
      .timeout(Duration.ofMinutes(8))
      .doOnNext(rows -> require(sourceIds(rows).size() <= 20000, "too_many"));
  }

  static Set<String> sourceIds(List<FacilitySync.Source> sources) {
    var ids = new HashSet<String>();
    for (var source : sources) for (var device : source.devices()) ids.add(device.getId());
    return ids;
  }

  public Mono<Integer> refresh() {
    // Obtain a complete, successful snapshot before any catalog write. Refresh never removes assets.
    return sources().flatMap(sources -> scope.current().flatMap(context -> {
      var visible = new LinkedHashMap<String, org.jetlinks.sdk.server.device.DeviceInfo>();
      for (var source : sources) for (var device : source.devices()) visible.put(device.getId(), device);
      return Flux.fromIterable(visible.values()).concatMap(source -> {
        String id = FacilityRecords.stableId(context.tenantId(), context.applicationId(), source.getId());
        return store.findById(id).flatMap(row -> {
          if (Objects.equals(row.getName(), source.getName()) && Objects.equals(row.getProductName(), source.getProductName())) return Mono.just(row);
          long version = row.getVersion();
          row.setName(source.getName()); row.setProductName(source.getProductName());
          return records.update(store, row, version, FacilityRecords.nullFields(row, "name", "productName"));
        }).switchIfEmpty(Mono.defer(() -> devices.findById(id)
          .map(old -> imported(context, source, old))
          .defaultIfEmpty(imported(context, source, null))
          .flatMap(row -> store.insert(row).thenReturn(row))));
      }).count().map(Long::intValue);
    }));
  }

  static DeviceAllocationEntity imported(FacilityContext context, org.jetlinks.sdk.server.device.DeviceInfo source, DeviceEntity old) {
    var row = FacilityScope.initialize(new DeviceAllocationEntity(), context);
    row.setId(FacilityRecords.stableId(context.tenantId(), context.applicationId(), source.getId()));
    row.setCloudDeviceId(source.getId()); row.setName(source.getName()); row.setProductName(source.getProductName());
    // Released confirmed allocations are retained. An old guessed project is not an explicit allocation.
    if (old != null && !Boolean.TRUE.equals(old.getDeleted()) && Boolean.TRUE.equals(old.getProjectConfirmed())) {
      row.setProjectId(old.getProjectId()); row.setOrganizationId(old.getOrganizationId()); row.setProjectName(old.getProjectName());
      row.setProjectConfirmed(true); row.setAllocationSince(old.getAllocationSince());
    }
    return row;
  }

  public Mono<PagerResult<DeviceAllocationEntity>> query(QueryParamEntity query) {
    query.setPaging(true); query.setPageSize(Math.min(100, Math.max(1, query.getPageSize())));
    return Mono.zip(sources(), scope.current()).flatMap(tuple -> {
      var context = tuple.getT2();
      var ids = sourceIds(tuple.getT1()).stream()
        .map(id -> FacilityRecords.stableId(context.tenantId(), context.applicationId(), id)).toList();
      if (ids.isEmpty()) return Mono.just(PagerResult.of(0, List.<DeviceAllocationEntity>of(), query));
      // 配置目录按 Cloud 来源授权；项目仅作为显式筛选，不因未归属或旧项目不在候选中隐藏设备。
      // 重新分配与同步仍分别校验原/新项目的 AssetsHolder 写权限。
      return store.createQuery().where("tenantId", context.tenantId()).and("applicationId", context.applicationId())
        .and("deleted", false).in("id", ids).fetch()
        .map(DeviceAllocationEntity::getId).collectList().flatMap(allowed -> {
          if (allowed.isEmpty()) return Mono.just(PagerResult.of(0, List.<DeviceAllocationEntity>of(), query));
          var scoped = FacilityScope.applicationQuery(query, context).toNestQuery().in("id", allowed).getParam();
          return store.queryPager(scoped).flatMap(page -> Flux.fromIterable(page.getData())
            .concatMap(row -> devices.findById(row.getId()).doOnNext(asset -> row.setAssetProjectId(asset.getProjectId())).thenReturn(row))
            .then(Mono.just(page)));
        });
    });
  }

  public Flux<DeviceAllocationEntity> configured(String projectId) {
    return scope.current().flatMapMany(context -> store.createQuery()
      .where("tenantId", context.tenantId()).and("applicationId", context.applicationId())
      .and("deleted", false).and("projectConfirmed", true).and("projectId", projectId).fetch());
  }

  @Transactional
  public Mono<List<DeviceAllocationEntity>> save(List<FacilityDevices.Allocation> inputs) {
    require(inputs != null && !inputs.isEmpty() && inputs.size() <= 200, "too_many");
    require(inputs.stream().allMatch(input -> input != null && input.id() != null && !input.id().isBlank()
      && input.projectId() != null && !input.projectId().isBlank()), "invalid_import");
    require(inputs.stream().map(FacilityDevices.Allocation::id).distinct().count() == inputs.size(), "invalid_import");
    return Mono.zip(sources(), scope.current()).flatMap(tuple -> {
      var context = tuple.getT2();
      var visible = sourceIds(tuple.getT1());
      return Flux.fromIterable(inputs).concatMap(input -> store.findById(input.id())
        .switchIfEmpty(Mono.error(new NotFoundException()))
        .flatMap(row -> {
          if (!context.tenantId().equals(row.getTenantId()) || !context.applicationId().equals(row.getApplicationId()) ||
              Boolean.TRUE.equals(row.getDeleted()) || !visible.contains(row.getCloudDeviceId())) return Mono.error(new AccessDenyException());
          require(row.getVersion() == input.version(), "conflict");
          return directory.project(input.projectId()).flatMap(project ->
            records.assertProject(project.getId(), CrudAssetPermission.save)
              .then(devices.findById(row.getId())
                .flatMap(asset -> moves.validate(asset, project.getId())
                  .then(Mono.defer(() -> records.assertPreviousProject(row.getProjectId())))
                  .thenReturn(true))
                // 未形成设备资产的配置仍校验原项目，不能借目录刷新绕过原归属写权限。
                .switchIfEmpty(Mono.defer(() -> (row.getProjectId() == null ? Mono.<Void>empty()
                  : records.assertProject(row.getProjectId(), CrudAssetPermission.save)).thenReturn(true))))
              .then(Mono.defer(() -> {
                boolean changed = !Objects.equals(row.getProjectId(), project.getId());
                row.setProjectId(project.getId()); row.setOrganizationId(project.getId()); row.setProjectName(project.getName());
                row.setProjectConfirmed(true);
                if (changed || row.getAllocationSince() == null) row.setAllocationSince(System.currentTimeMillis());
                return records.update(store, row, input.version());
              })));
        })).collectList();
    });
  }
}
