package org.jetlinks.pro.property;

import org.hswebframework.web.authorization.basic.configuration.EnableAopAuthorize;
import org.hswebframework.web.crud.annotation.EnableEasyormRepository;
import org.hswebframework.web.logging.aop.EnableAccessLogger;
import org.jetlinks.pro.property.quality.asset.QualityOrganizationAssetsConfiguration;
import org.jetlinks.pro.property.configuration.PropertyOrganizationAssetsConfiguration;
import org.jetlinks.pro.smartpark.repair.asset.RepairAssetsConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 物业统一启动入口，在一个进程和一个数据源中装配八个业务模块。
 * 业务模块仍通过既有公开命令保留认证与资产权限边界；认证、IoT 和文件提供方由 SaaS 承载。
 * 平台插件管理接口不属于物业业务；多个模块的组织权限提供者由统一配置显式装配。
 *
 * @see PropertyOrganizationAssetsConfiguration
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@ComponentScan(basePackages = "org.jetlinks.pro", excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
        // 平台测试依赖附带另一启动工程，不能让其无差别扫描覆盖本工程的装配边界。
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = {
                "org\\.jetlinks\\.pro\\.test\\..*", "org\\.jetlinks\\.pro\\.plugin\\.web\\..*"}),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = {
                "org\\.jetlinks\\.pro\\.io\\.file\\.web\\..*",
                "org\\.jetlinks\\.pro\\.io\\.command\\..*"}),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                RepairAssetsConfiguration.class,
                QualityOrganizationAssetsConfiguration.class})
})
@EnableScheduling
@EnableCaching
@EnableAccessLogger
@EnableAopAuthorize
@EnableEasyormRepository("org.jetlinks.pro.**.entity")
public class PropertyManagementApplication {
    public static void main(String[] args) {
        SpringApplication.run(PropertyManagementApplication.class, args);
    }
}
