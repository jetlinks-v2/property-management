package org.jetlinks.pro.property.finance.service;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 自动月结扫描间隔；业务失败的项目持久化等待一小时再重试。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "property.finance.month-closing-job")
public class MonthClosingJobProperties {
    private boolean enabled = true;
    private int pollSeconds = 60;
}
