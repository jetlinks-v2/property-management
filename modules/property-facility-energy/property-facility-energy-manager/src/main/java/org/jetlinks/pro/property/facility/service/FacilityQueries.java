package org.jetlinks.pro.property.facility.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.executor.wrapper.ResultWrappers;
import org.hswebframework.ezorm.rdb.operator.dml.query.SelectColumn;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.*;

/** 受控查询及逻辑删除，客户端不能选择数据表或权限资产类型。 */
@Service
@RequiredArgsConstructor
public class FacilityQueries {

  private final FacilityScope scope;
  private final FacilityRecords records;
  private final FacilityDirectory directory;
  private final FacilityDevices devices;

  public <T extends FacilityEntity> Mono<PagerResult<T>> page(
    GenericReactiveCrudService<T, String> store,
    QueryParamEntity query,
    AssetType type,
    String field
  ) {
    return scope.query(query, type, field).flatMap(store::queryPager);
  }

  /** 子记录保留设备/计划资产授权，同时排除已失效或不可见的项目。 */
  public <T extends FacilityEntity> Mono<PagerResult<T>> childrenInProjects(
    GenericReactiveCrudService<T, String> store, QueryParamEntity query, AssetType type, String field
  ) {
    return directory.projects().map(org -> org.getId()).distinct().collectList().flatMap(ids -> ids.isEmpty()
      ? Mono.just(PagerResult.of(0, java.util.List.<T>of(), query))
      : page(store, query.clone().toNestQuery().in("projectId", ids).getParam(), type, field));
  }

  /** 先按原查询及项目权限取候选设备，当前绑定交集在分页和总数之前生效。 */
  public <T extends FacilityEntity> Mono<PagerResult<T>> currentDeviceChildren(
    GenericReactiveCrudService<T, String> store, QueryParamEntity query, AssetType type, String field, boolean projectScoped
  ) {
    var projectQuery = projectScoped
      ? directory.projects().map(org -> org.getId()).distinct().collectList().filter(ids -> !ids.isEmpty())
        .map(ids -> query.clone().toNestQuery().in("projectId", ids).getParam())
      : Mono.just(query);
    return projectQuery.flatMap(param -> scope.query(param, type, field))
      .flatMap(authorized -> {
        var candidates = authorized.clone().toNestQuery().notNull("deviceId").getParam();
        candidates.setPaging(false);
        candidates.setForUpdate(false);
        candidates.setSorts(List.of());
        return store.getRepository().nativeQuery().setParam(candidates)
          .select(SelectColumn.of("deviceId", "deviceId")).groupBy(SelectColumn.of("deviceId"))
          .paging(0, 20001).fetch(ResultWrappers.map()).reactive().collectList()
          .doOnNext(rows -> FacilityScope.require(rows.size() <= 20000, "too_many"))
          .flatMap(rows -> {
            if (rows.isEmpty()) return Mono.just(PagerResult.of(0, List.<T>of(), authorized));
            var ids = rows.stream().map(row -> row.get("deviceId").toString()).toList();
            return devices.currentIds(QueryParamEntity.newQuery().in("id", ids).getParam())
              .flatMap(bound -> bound.isEmpty() ? Mono.just(PagerResult.of(0, List.<T>of(), authorized))
                : store.queryPager(authorized.toNestQuery().in("deviceId", bound).getParam()));
          });
      })
      .defaultIfEmpty(PagerResult.of(0, List.<T>of(), query));
  }

  /**
   * Shared organizations live outside this database. Their public directory command owns
   * AssetsHolder filtering, including tenant membership; never expand its asset SQL locally.
   * Intersect the authorized project IDs with this application's records before pagination.
   */
  public <T extends FacilityEntity> Mono<PagerResult<T>> projects(
    GenericReactiveCrudService<T, String> store,
    QueryParamEntity query
  ) {
    return scope.applicationQuery(query).flatMap(scoped -> directory.projects()
      .map(org -> org.getId()).distinct().collectList()
      .flatMap(ids -> ids.isEmpty()
        ? Mono.just(PagerResult.of(0, java.util.List.<T>of(), scoped))
        : store.queryPager(scoped.toNestQuery().in("projectId", ids).getParam())));
  }

  @Transactional
  public <T extends FacilityEntity> Mono<Void> remove(
    GenericReactiveCrudService<T, String> store,
    String id,
    long version,
    AssetType type
  ) {
    return records
      .read(store, id, type, CrudAssetPermission.delete)
      .flatMap(row -> {
        FacilityScope.require(row.getVersion() == version, "conflict");
        row.setDeleted(true);
        return records
          .update(store, row, version)
          .then(
            records.log(
              row,
              "configuration",
              "delete",
              null,
              null,
              null,
              java.util.List.of()
            )
          );
      });
  }
}
