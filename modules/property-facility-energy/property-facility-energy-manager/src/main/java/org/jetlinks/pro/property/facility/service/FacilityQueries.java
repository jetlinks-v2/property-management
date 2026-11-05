package org.jetlinks.pro.property.facility.service;

import lombok.RequiredArgsConstructor;
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

  public <T extends FacilityEntity> Mono<PagerResult<T>> page(
    GenericReactiveCrudService<T, String> store,
    QueryParamEntity query,
    AssetType type,
    String field
  ) {
    return scope.query(query, type, field).flatMap(store::queryPager);
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
