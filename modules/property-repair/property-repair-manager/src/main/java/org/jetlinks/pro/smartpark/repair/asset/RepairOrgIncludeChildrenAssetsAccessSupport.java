package org.jetlinks.pro.smartpark.repair.asset;

import org.hswebframework.ezorm.core.Conditional;
import org.hswebframework.ezorm.core.NestConditional;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.pro.assets.AssetManager;
import org.jetlinks.pro.assets.AssetPermission;
import org.jetlinks.pro.assets.impl.supports.OrgIncludeChildrenAssetsAccessSupport;
import reactor.core.publisher.Mono;

/**
 * 报修独立数据库的“所在组织及下级”权限提供者。
 * 查询使用平台筛选后的完整组织维度，沿用原 supportId、资产绑定及创建人回退；
 * 组织资产本身继续由框架的专用 Holder 处理。
 *
 * @see RepairAssetsConfiguration
 * @see OrgIncludeChildrenAssetsAccessSupport
 */
public class RepairOrgIncludeChildrenAssetsAccessSupport extends OrgIncludeChildrenAssetsAccessSupport {
    public RepairOrgIncludeChildrenAssetsAccessSupport(AssetManager assetManager) {
        super(assetManager);
    }

    @Override
    public <T extends Conditional<T>> Mono<NestConditional<T>> injectConditional0(
        NestConditional<T> conditional, Authentication authentication, String assetType,
        String idProperty, AssetPermission... permissions) {
        if ("organization".equals(assetType)) {
            return super.injectConditional0(conditional, authentication, assetType, idProperty, permissions);
        }
        return getDimensionsForQuery(assetType, authentication).collectList()
            .flatMap(dimensions -> ExpandedOrganizationQuery.inject(
                conditional, dimensions, assetType, idProperty, permissions));
    }
}
