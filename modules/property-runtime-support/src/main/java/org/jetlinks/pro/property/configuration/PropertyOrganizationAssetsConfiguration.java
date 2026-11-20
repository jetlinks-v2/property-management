package org.jetlinks.pro.property.configuration;

import org.hswebframework.ezorm.core.Conditional;
import org.hswebframework.ezorm.core.NestConditional;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.pro.assets.AssetManager;
import org.jetlinks.pro.assets.AssetPermission;
import org.jetlinks.pro.assets.impl.supports.OrgIncludeChildrenAssetsAccessSupport;
import org.jetlinks.pro.assets.impl.supports.OrgIncludeParentAssetsAccessSupport;
import org.jetlinks.pro.property.quality.asset.QualityOrganizationAssetsConfiguration;
import org.jetlinks.pro.smartpark.repair.asset.RepairOrgIncludeChildrenAssetsAccessSupport;
import org.jetlinks.pro.smartpark.repair.asset.RepairOrgIncludeParentAssetsAccessSupport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.smartpark.repair.asset.RepairAssetType;
import org.jetlinks.pro.smartpark.handover.asset.HandoverAssetType;

/**
 * 统一进程只注册一组组织上下级权限提供者，避免原独立服务的同名 Bean 相互覆盖。
 * 复用原报修的展开组织查询和空范围保护；品质资产仍委托原专用规则，保留上级只读及绑定边界。
 * 本配置不改变 supportId、资产类型或调用者认证，不从业务库推断平台组织授权。
 *
 * @see RepairOrgIncludeParentAssetsAccessSupport
 * @see QualityOrganizationAssetsConfiguration
 */
@Configuration(proxyBeanMethods = false)
public class PropertyOrganizationAssetsConfiguration {
    private static final Set<String> QUALITY_TYPES =
            Set.of("qualityConfiguration", "qualityTask", "qualityWorkOrder");

    // 只替换物业资产的查询实现，其他应用继续使用平台原规则。
    private static final Set<String> PROPERTY_TYPES = Stream.concat(Stream.of("space"),
            Stream.of(PatrolAssetType.values(), FacilityAssetType.values(),
                      RepairAssetType.values(), HandoverAssetType.values())
                    .flatMap(Arrays::stream).map(Enum::name)).collect(Collectors.toUnmodifiableSet());

    @Bean
    public OrgIncludeParentAssetsAccessSupport orgIncludeParentAssetsAccessSupport(AssetManager manager) {
        var quality = new QualityOrganizationAssetsConfiguration().orgIncludeParentAssetsAccessSupport(manager);
        var propertySupport = new RepairOrgIncludeParentAssetsAccessSupport(manager);
        return new OrgIncludeParentAssetsAccessSupport(manager) {
            @Override
            public <T extends Conditional<T>> Mono<NestConditional<T>> injectConditional0(
                    NestConditional<T> conditional, Authentication authentication, String assetType,
                    String property, AssetPermission... permissions) {
                return QUALITY_TYPES.contains(assetType)
                        ? quality.injectConditional0(conditional, authentication, assetType, property, permissions)
                        : PROPERTY_TYPES.contains(assetType)
                            ? propertySupport.injectConditional0(conditional, authentication, assetType, property, permissions)
                            : super.injectConditional0(conditional, authentication, assetType, property, permissions);
            }
        };
    }

    @Bean
    public OrgIncludeChildrenAssetsAccessSupport orgIncludeChildrenAssetsAccessSupport(AssetManager manager) {
        var quality = new QualityOrganizationAssetsConfiguration().orgIncludeChildrenAssetsAccessSupport(manager);
        var propertySupport = new RepairOrgIncludeChildrenAssetsAccessSupport(manager);
        return new OrgIncludeChildrenAssetsAccessSupport(manager) {
            @Override
            public <T extends Conditional<T>> Mono<NestConditional<T>> injectConditional0(
                    NestConditional<T> conditional, Authentication authentication, String assetType,
                    String property, AssetPermission... permissions) {
                return QUALITY_TYPES.contains(assetType)
                        ? quality.injectConditional0(conditional, authentication, assetType, property, permissions)
                        : PROPERTY_TYPES.contains(assetType)
                            ? propertySupport.injectConditional0(conditional, authentication, assetType, property, permissions)
                            : super.injectConditional0(conditional, authentication, assetType, property, permissions);
            }
        };
    }
}
