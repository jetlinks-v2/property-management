package org.jetlinks.pro.patrol;

import org.hswebframework.ezorm.core.Conditional;
import org.hswebframework.ezorm.core.NestConditional;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.pro.assets.AssetManager;
import org.jetlinks.pro.assets.AssetPermission;
import org.jetlinks.pro.assets.impl.supports.OrgIncludeParentAssetsAccessSupport;
import reactor.core.publisher.Mono;

/**
 * 为公共组件的组织及上下级查询补齐空组织范围。
 * 由巡更独立服务配置创建，沿用组织提供者的资产、创建人回退及写权限契约；
 * 仅在查询不存在有效组织维度时生成空范围，避免优化分支构造无目标的资产条件。
 * 公共组件修复同一分支后移除此兼容实现。
 *
 * @see OrgIncludeParentAssetsAccessSupport
 */
public class EmptySafeOrgIncludeParentAssetsAccessSupport extends OrgIncludeParentAssetsAccessSupport {

    public EmptySafeOrgIncludeParentAssetsAccessSupport(AssetManager assetManager) {
        super(assetManager);
    }

    @Override
    public <T extends Conditional<T>> Mono<NestConditional<T>> injectConditional0(
        NestConditional<T> conditional,
        Authentication authentication,
        String assetType,
        String idProperty,
        AssetPermission... permission) {
        if (AssetPermission.isForQuery(permission)
            && authentication.getDimensions().stream().noneMatch(dimension ->
                isSupported(dimension) && testForQuery(dimension))) {
            // 空组织只关闭组织分支，不能报错中断其他租户/创建人分支，也不能放开查询。
            conditional.nest().isNull(idProperty).and().notNull(idProperty).end();
            return Mono.just(conditional);
        }
        return super.injectConditional0(conditional, authentication, assetType, idProperty, permission);
    }
}
