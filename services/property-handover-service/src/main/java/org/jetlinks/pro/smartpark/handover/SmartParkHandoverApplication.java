package org.jetlinks.pro.smartpark.handover;

import org.hswebframework.web.authorization.basic.configuration.EnableAopAuthorize;
import org.hswebframework.web.crud.annotation.EnableEasyormRepository;
import org.hswebframework.web.logging.aop.EnableAccessLogger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * 承接查验独立服务启动入口。
 *
 * <p>复用平台运行时认证，基础数据通过公共只读命令访问独立基础服务。
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@ComponentScan(
        basePackages = "org.jetlinks.pro",
        excludeFilters = {
            @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
            @ComponentScan.Filter(
                    type = FilterType.CUSTOM,
                    classes = AutoConfigurationExcludeFilter.class),
            @ComponentScan.Filter(
                    type = FilterType.REGEX,
                    pattern = "org\\.jetlinks\\.pro\\.plugin\\.web\\..*")
        })
@org.springframework.scheduling.annotation.EnableScheduling
@EnableCaching
@EnableAccessLogger
@EnableAopAuthorize
@EnableEasyormRepository("org.jetlinks.pro.**.entity")
public class SmartParkHandoverApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartParkHandoverApplication.class, args);
    }
}
