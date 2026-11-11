package org.jetlinks.pro.smartpark.repair.asset;

import org.hswebframework.ezorm.core.Conditional;
import org.hswebframework.ezorm.core.NestConditional;
import org.hswebframework.web.authorization.Dimension;
import org.jetlinks.pro.assets.AssetPermission;
import org.jetlinks.pro.assets.impl.terms.DimensionAssetsTerm;
import org.jetlinks.pro.assets.impl.terms.DimensionAssetsTermBuilder;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 将组织权限提供者已筛选、转换的认证维度注入业务查询。
 * 认证服务负责展开层级，本类不查询或复制组织表，也不改变创建人、绑定和租户授权。
 */
final class ExpandedOrganizationQuery {
    private ExpandedOrganizationQuery() {
    }

    static <T extends Conditional<T>> Mono<NestConditional<T>> inject(
        NestConditional<T> conditional, List<Dimension> dimensions, String assetType,
        String idProperty, AssetPermission... permissions) {
        if (dimensions.isEmpty()) {
            // 空组织仅拒绝当前组织分支；不能省略条件后让组合 Holder 放开数据范围。
            conditional.nest().isNull(idProperty).and().notNull(idProperty).end();
            return Mono.just(conditional);
        }
        // 认证维度已包含授权下级；独立报修库不能再依赖认证库的闭包表展开一次。
        DimensionAssetsTerm term = DimensionAssetsTerm.of(dimensions, assetType, false, permissions);
        term.setClosure(false);
        return term.applyContext().thenReturn(
            conditional.accept(idProperty, DimensionAssetsTermBuilder.termType, term));
    }
}
