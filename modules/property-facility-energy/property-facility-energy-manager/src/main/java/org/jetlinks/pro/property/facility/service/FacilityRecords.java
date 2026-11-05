package org.jetlinks.pro.property.facility.service;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.exception.*;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;

/** 记录级权限及乐观并发的唯一入口；子记录从所属设备或任务继承范围。 */
@Service
@RequiredArgsConstructor
public class FacilityRecords {

  private final FacilityScope scope;
  private final ProcessStore processes;

  public <T extends FacilityEntity> Mono<T> read(
    org.hswebframework.web.crud.service.GenericReactiveCrudService<
      T,
      String
    > store,
    String id,
    AssetType type,
    CrudAssetPermission action
  ) {
    return store
      .findById(id)
      .switchIfEmpty(Mono.error(new NotFoundException()))
      .flatMap(row ->
        scope.assertRecord(row, type, row.getId(), action).thenReturn(row)
      );
  }

  public <T extends FacilityEntity> Mono<T> insert(
    org.hswebframework.web.crud.service.GenericReactiveCrudService<
      T,
      String
    > store,
    T row,
    AssetType type
  ) {
    return store
      .insert(row)
      .then(
        type == null
          ? Mono.just(row)
          : AssetsHolder.bindAssets(
              Flux.just(row),
              type,
              FacilityEntity::getId
            ).single()
      );
  }

  public <T extends FacilityEntity> Mono<T> update(
    org.hswebframework.web.crud.service.GenericReactiveCrudService<
      T,
      String
    > store,
    T row,
    long version,
    String... clearFields
  ) {
    row.setVersion(version + 1);
    row.setModifyTime(System.currentTimeMillis());
    var update = store.createUpdate().set(row);
    // EasyORM omits null entity properties; lifecycle resets must explicitly persist SQL NULL.
    for (String field : clearFields) update.setNull(field);
    return update
      .where("id", row.getId())
      .and("tenantId", row.getTenantId())
      .and("applicationId", row.getApplicationId())
      .and("version", version)
      .execute()
      .doOnNext(n -> FacilityScope.require(n == 1, "conflict"))
      .thenReturn(row);
  }

  /** Full-form updates explicitly clear only named business fields; source and scope fields are never inferred. */
  public static String[] nullFields(Object row, String... editableFields) {
    var bean = new org.springframework.beans.BeanWrapperImpl(row);
    return java.util.Arrays.stream(editableFields)
      .filter(field -> bean.getPropertyValue(field) == null)
      .toArray(String[]::new);
  }

  public Mono<Void> log(
    FacilityEntity parent,
    String type,
    String action,
    String from,
    String to,
    String comment,
    List<String> files
  ) {
    return scope.current().flatMap(c -> {
      var row = FacilityScope.initialize(new ProcessEntity(), c);
      FacilityScope.inherit(row, parent);
      row.setTargetId(parent.getId());
      row.setTargetType(type);
      row.setAction(action);
      row.setFromState(from);
      row.setToState(to);
      row.setComment(comment);
      row.setAttachments(files == null ? List.of() : files);
      return processes.insert(row).then();
    });
  }

  public <T extends FacilityEntity> Mono<T> child(
    T row,
    FacilityEntity parent
  ) {
    return scope.current().flatMap(c -> {
      if (
        !Objects.equals(c.tenantId(), row.getTenantId()) ||
        !Objects.equals(c.applicationId(), row.getApplicationId()) ||
        !Objects.equals(parent.getProjectId(), row.getProjectId()) ||
        Boolean.TRUE.equals(row.getDeleted())
      ) return Mono.error(new AccessDenyException());
      return Mono.just(row);
    });
  }

  public Mono<Void> assertProject(String id, CrudAssetPermission permission) {
    return AssetsHolder.assertPermission(
      AssetType.of("organization"),
      id,
      permission
    );
  }

  public static String stableId(String... values) {
    return java.util.UUID.nameUUIDFromBytes(
      String.join(":", values).getBytes(java.nio.charset.StandardCharsets.UTF_8)
    )
      .toString()
      .replace("-", "");
  }
}
