package org.jetlinks.pro.patrol;

import org.jetlinks.pro.assets.AssetManager;
import org.jetlinks.pro.assets.impl.supports.OrgIncludeParentAssetsAccessSupport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 巡更独立服务的资产组件兼容配置，在框架构造权限提供者前替换组织范围实现。
 * 保留原 Bean 名和 supportId，避免同一组织范围注册多个提供者。
 *
 * @see EmptySafeOrgIncludeParentAssetsAccessSupport
 */
@Configuration(proxyBeanMethods = false)
public class PatrolAssetsConfiguration {

    @Bean
    public OrgIncludeParentAssetsAccessSupport orgIncludeParentAssetsAccessSupport(AssetManager assetManager) {
        return new EmptySafeOrgIncludeParentAssetsAccessSupport(assetManager);
    }
}
