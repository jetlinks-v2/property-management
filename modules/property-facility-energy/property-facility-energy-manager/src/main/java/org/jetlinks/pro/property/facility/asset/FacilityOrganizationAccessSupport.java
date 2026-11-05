package org.jetlinks.pro.property.facility.asset;

import java.util.Arrays;
import org.hswebframework.ezorm.core.Conditional;
import org.hswebframework.ezorm.core.NestConditional;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.Dimension;
import org.jetlinks.pro.assets.AssetManager;
import org.jetlinks.pro.assets.AssetPermission;
import org.jetlinks.pro.assets.impl.supports.OrgAssetsAccessSupport;
import org.jetlinks.pro.assets.impl.supports.OrgIncludeChildrenAssetsAccessSupport;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 设施服务的组织及下级组织查询策略，复用认证服务已展开的授权维度。
 *
 * <p>由 AssetsHolder 注册并调用，沿用同一策略 ID、绑定规则及创建人权限。
 * 设施库不持有 Cloud 的组织闭包数据，因此仅设施资产跳过本地闭包 SQL 优化；
 * 其他资产仍委托平台策略处理，不改变租户和当前应用的独立限制。
 *
 * @see OrgIncludeChildrenAssetsAccessSupport
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class FacilityOrganizationAccessSupport extends OrgAssetsAccessSupport {

  private final OrgIncludeChildrenAssetsAccessSupport platform;

  public FacilityOrganizationAccessSupport(AssetManager manager) {
    // 同 ID 按 priority 注册，晚于平台的 200，保留已有角色授权契约。
    super("org-include-children", "所在组织及下级组织", 201, manager);
    platform = new OrgIncludeChildrenAssetsAccessSupport(manager);
  }

  @Override
  protected boolean testForQuery(Dimension dimension) {
    // 与平台下级组织策略一致：包含已展开的子级，排除仅作祖先导航的维度。
    return !isIgnored(dimension);
  }

  @Override
  public <T extends Conditional<T>> Mono<NestConditional<T>> injectConditional0(
    NestConditional<T> conditional,
    Authentication authentication,
    String assetType,
    String idProperty,
    AssetPermission... permission
  ) {
    boolean facility = Arrays.stream(FacilityAssetType.values()).anyMatch(
      type -> type.isSameType(assetType)
    );
    return facility
      ? super.injectConditional0(
          conditional,
          authentication,
          assetType,
          idProperty,
          permission
        )
      : platform.injectConditional0(
          conditional,
          authentication,
          assetType,
          idProperty,
          permission
        );
  }
}
