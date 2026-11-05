package org.jetlinks.pro.property.decoration;

import org.hswebframework.web.authorization.basic.configuration.EnableAopAuthorize;
import org.hswebframework.web.crud.annotation.EnableEasyormRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;

/**
 * 装修管理独立启动入口，只装配本系统 manager 和 SaaS 公共组件。
 * 业务依赖由本服务 POM 限定，不加载其他物业系统。
 */
@SpringBootApplication(scanBasePackages = "org.jetlinks.pro", exclude = DataSourceAutoConfiguration.class)
@EnableCaching
@EnableAopAuthorize
@EnableEasyormRepository("org.jetlinks.pro.**.entity")
public class PropertyDecorationApplication {

    public static void main(String[] args) {
        SpringApplication.run(PropertyDecorationApplication.class, args);
    }
}
