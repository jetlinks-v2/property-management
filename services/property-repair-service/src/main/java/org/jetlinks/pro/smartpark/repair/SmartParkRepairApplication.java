package org.jetlinks.pro.smartpark.repair;

import org.hswebframework.web.authorization.basic.configuration.EnableAopAuthorize;
import org.hswebframework.web.crud.annotation.EnableEasyormRepository;
import org.hswebframework.web.logging.aop.EnableAccessLogger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * 报事报修与物业共享基础数据服务启动入口。
 *
 * 复用平台运行时认证，装配报事领域和多应用共享的物业基础数据表。
 */
@SpringBootApplication(
    exclude = DataSourceAutoConfiguration.class)
@ComponentScan(
    basePackages = "org.jetlinks.pro",
    excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
        @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = "org\\.jetlinks\\.pro\\.plugin\\.web\\..*")
    })
@EnableCaching
@EnableAccessLogger
@EnableAopAuthorize
@EnableEasyormRepository("org.jetlinks.pro.**.entity")
public class SmartParkRepairApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartParkRepairApplication.class, args);
    }
}
