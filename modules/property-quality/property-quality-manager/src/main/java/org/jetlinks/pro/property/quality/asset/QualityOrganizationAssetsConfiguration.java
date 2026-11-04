package org.jetlinks.pro.property.quality.asset;

import org.hswebframework.ezorm.core.Conditional;
import org.hswebframework.ezorm.core.NestConditional;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.pro.assets.AssetManager;
import org.jetlinks.pro.assets.AssetPermission;
import org.jetlinks.pro.assets.impl.supports.DimensionAccessSupport;
import org.jetlinks.pro.assets.impl.supports.OrgIncludeChildrenAssetsAccessSupport;
import org.jetlinks.pro.assets.impl.supports.OrgIncludeParentAssetsAccessSupport;
import org.jetlinks.pro.assets.impl.terms.DimensionAssetsTerm;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * 品质库不复制 Cloud 的组织闭包表。组织范围由平台认证展开，查询与详情使用相同维度。
 * 保留原 supportId、创建人回退和读写语义，仅替换依赖本地闭包表的查询优化。
 */
@Configuration(proxyBeanMethods = false)
public class QualityOrganizationAssetsConfiguration {

    @Bean
    public OrgIncludeChildrenAssetsAccessSupport orgIncludeChildrenAssetsAccessSupport(AssetManager manager) {
        return new OrgIncludeChildrenAssetsAccessSupport(manager) {
            @Override
            public <T extends Conditional<T>> Mono<NestConditional<T>> injectConditional0(
                NestConditional<T> conditional, Authentication authentication, String assetType,
                String property, AssetPermission... permissions) {
                if (isQualityAsset(assetType)) {
                    return injectExpanded(this, conditional, authentication, assetType, property, permissions);
                }
                return super.injectConditional0(conditional, authentication, assetType, property, permissions);
            }
        };
    }

    @Bean
    public OrgIncludeParentAssetsAccessSupport orgIncludeParentAssetsAccessSupport(AssetManager manager) {
        return new OrgIncludeParentAssetsAccessSupport(manager) {
            @Override
            public <T extends Conditional<T>> Mono<NestConditional<T>> injectConditional0(
                NestConditional<T> conditional, Authentication authentication, String assetType,
                String property, AssetPermission... permissions) {
                if (isQualityAsset(assetType)) {
                    return injectExpanded(this, conditional, authentication, assetType, property, permissions);
                }
                return super.injectConditional0(conditional, authentication, assetType, property, permissions);
            }
        };
    }

    private static boolean isQualityAsset(String type) {
        return type.equals("qualityConfiguration") || type.equals("qualityTask") || type.equals("qualityWorkOrder");
    }

    static <T extends Conditional<T>> Mono<NestConditional<T>> injectExpanded(
        DimensionAccessSupport support, NestConditional<T> conditional, Authentication authentication,
        String assetType, String property, AssetPermission... permissions) {
        // 写入只允许绑定组织；上下级范围的上级组织仍然只读。
        var dimensions = AssetPermission.isForQuery(permissions)
            ? support.getDimensionsForQuery(assetType, authentication)
            : support.getDimensionsForBind(assetType, authentication);
        return dimensions.collectList().flatMap(values -> {
            if (values.isEmpty()) {
                conditional.nest().isNull(property).and().notNull(property).end();
                return Mono.just(conditional);
            }
            var term = DimensionAssetsTerm.of(values, assetType, false, permissions);
            return term.applyContext().thenReturn(conditional.accept(property, "dim-assets", term));
        });
    }
}
