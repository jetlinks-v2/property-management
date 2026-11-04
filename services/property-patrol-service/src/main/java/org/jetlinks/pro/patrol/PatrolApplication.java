package org.jetlinks.pro.patrol;

import org.hswebframework.web.authorization.basic.configuration.EnableAopAuthorize;
import org.hswebframework.web.crud.annotation.EnableEasyormRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;

/** 巡更独立启动服务；平台认证、发现和响应包装沿用 SaaS 微服务装配。 */
@SpringBootApplication(
        scanBasePackages = "org.jetlinks.pro",
        exclude = DataSourceAutoConfiguration.class)
@EnableCaching
@EnableAopAuthorize
@EnableEasyormRepository("org.jetlinks.pro.**.entity")
public class PatrolApplication {
    public static void main(String[] args) {
        SpringApplication.run(PatrolApplication.class, args);
    }
}
