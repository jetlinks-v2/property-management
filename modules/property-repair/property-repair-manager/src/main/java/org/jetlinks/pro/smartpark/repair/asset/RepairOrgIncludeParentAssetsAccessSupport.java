package org.jetlinks.pro.smartpark.repair.asset;

import org.hswebframework.ezorm.core.Conditional;
import org.hswebframework.ezorm.core.NestConditional;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.pro.assets.AssetManager;
import org.jetlinks.pro.assets.AssetPermission;
import org.jetlinks.pro.assets.impl.supports.OrgIncludeParentAssetsAccessSupport;
import reactor.core.publisher.Mono;

/**
 * 报修独立数据库的“所在组织及上下级”权限提供者。
 * 读取使用框架筛选并转换的组织维度，避免依赖认证库闭包表；
 * 上级只读、写入范围、资产绑定及创建人回退仍由原提供者控制。
 *
 * @see OrgIncludeParentAssetsAccessSupport
 */
public class RepairOrgIncludeParentAssetsAccessSupport extends OrgIncludeParentAssetsAccessSupport {

    public RepairOrgIncludeParentAssetsAccessSupport(AssetManager assetManager) {
        super(assetManager);
    }

    @Override
    public <T extends Conditional<T>> Mono<NestConditional<T>> injectConditional0(
        NestConditional<T> conditional,
        Authentication authentication,
        String assetType,
        String idProperty,
        AssetPermission... permission) {
        if (AssetPermission.isForQuery(permission) && !"organization".equals(assetType)) {
            // 复用平台的忽略规则及 parentOrg 转换，不能手动把上级加入写范围。
            return getDimensionsForQuery(assetType, authentication).collectList()
                .flatMap(dimensions -> ExpandedOrganizationQuery.inject(
                    conditional, dimensions, assetType, idProperty, permission));
        }
        return super.injectConditional0(conditional, authentication, assetType, idProperty, permission);
    }
}
