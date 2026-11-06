package org.jetlinks.pro.property.facility.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.id.IDGenerator;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.assets.scope.AssetScopeActivation;
import org.jetlinks.pro.assets.scope.AssetScopeContext;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.property.facility.entity.FacilityEntity;
import org.jetlinks.pro.tenant.assets.TenantAssetsHolder;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 设施设备能源所有独立入口的授权 owner；统一与请求租户、当前应用及 AssetsHolder 取交集。 */
@Component
@RequiredArgsConstructor
public class FacilityScope {

  private final AssetManager manager;

  public Mono<FacilityContext> current() {
    return Mono.zip(
      TenantHolder.currentId(),
      AssetScopeContext.currentReactive().flatMap(s ->
        Mono.justOrEmpty(s.getScope(BusinessApplicationDimension.TYPE))
      ),
      Authentication.currentReactive()
    )
      .filter(t -> hasApplicationMembership(t.getT3(), t.getT2().id()))
      .map(t ->
        new FacilityContext(
          t.getT1(),
          t.getT2().id(),
          t.getT3().getUser().getId(),
          t.getT3().getUser().getName()
        )
      )
      .switchIfEmpty(Mono.error(new AccessDenyException()))
      .flatMap(c ->
        tenant(c)
          .assertPermission0(
            AssetType.of("business_application"),
            List.of(c.applicationId()),
            CrudAssetPermission.read
          )
          .thenReturn(c)
      );
  }

  /** Requested scopes may contain synthetic dimensions; they are not application members. */
  static boolean hasApplicationMembership(
    Authentication authentication,
    String applicationId
  ) {
    return authentication
      .getDimension(BusinessApplicationDimension.TYPE, applicationId)
      .filter(dimension -> !AssetScopeActivation.isScopeOnly(dimension))
      .isPresent();
  }

  private TenantAssetsHolder tenant(FacilityContext c) {
    return new TenantAssetsHolder(List.of(c.tenantId()), manager);
  }

  public Mono<QueryParamEntity> query(
    QueryParamEntity query,
    AssetType type,
    String property
  ) {
    return current().flatMap(c -> {
      var param = applicationQuery(query, c);
      return AssetsHolder.injectQueryParam(param, type, property).flatMap(q ->
        tenant(c).injectQuery(
          q,
          type.getId(),
          property,
          CrudAssetPermission.read
        )
      );
    });
  }

  /** Only for directory-authorized project queries; record-level asset queries use query above. */
  Mono<QueryParamEntity> applicationQuery(QueryParamEntity query) {
    return current().map(c -> applicationQuery(query, c));
  }

  static QueryParamEntity applicationQuery(
    QueryParamEntity query,
    FacilityContext context
  ) {
    var param = query
      .clone()
      .toNestQuery()
      .and("tenantId", context.tenantId())
      .and("applicationId", context.applicationId())
      .and("deleted", false)
      .getParam();
    if (param.getSorts().isEmpty()) param
      .toQuery()
      .orderByDesc("createTime")
      .orderByDesc("id");
    param.setPageSize(Math.min(Math.max(param.getPageSize(), 1), 200));
    return param;
  }

  public Mono<Void> assertRecord(
    FacilityEntity row,
    AssetType type,
    String assetId,
    CrudAssetPermission permission
  ) {
    return current().flatMap(c -> {
      if (
        Boolean.TRUE.equals(row.getDeleted()) ||
        !c.applicationId().equals(row.getApplicationId()) ||
        !c.tenantId().equals(row.getTenantId())
      ) return Mono.error(new AccessDenyException());
      return tenant(c)
        .assertPermission0(type, List.of(assetId), CrudAssetPermission.read)
        .then(AssetsHolder.assertPermission(type, assetId, permission));
    });
  }

  public static <T extends FacilityEntity> T initialize(
    T row,
    FacilityContext c
  ) {
    row.setId(IDGenerator.MD5.generate());
    row.setApplicationId(c.applicationId());
    row.setTenantId(c.tenantId());
    row.setCreatorId(c.userId());
    row.setCreatorName(c.userName());
    row.setCreateTime(System.currentTimeMillis());
    row.setModifyTime(row.getCreateTime());
    row.setVersion(0L);
    row.setDeleted(false);
    return row;
  }

  public static void inherit(FacilityEntity row, FacilityEntity parent) {
    row.setApplicationId(parent.getApplicationId());
    row.setTenantId(parent.getTenantId());
    row.setProjectId(parent.getProjectId());
    row.setProjectName(parent.getProjectName());
    row.setOrganizationId(parent.getOrganizationId());
  }

  public static void require(boolean valid, String code) {
    if (!valid) throw new org.hswebframework.web.exception.BusinessException(
      "error.facility." + code,
      code.equals("conflict") ? 409 : 400
    );
  }
}
