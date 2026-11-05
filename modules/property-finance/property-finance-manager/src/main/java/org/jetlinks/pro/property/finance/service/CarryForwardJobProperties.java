package org.jetlinks.pro.property.finance.service;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 扫描间隔与计划的日/周/月/年执行时间独立；每轮每计划最多处理一批。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "property.finance.carry-job")
public class CarryForwardJobProperties {
    private boolean enabled = true;
    private int pollSeconds = 30;
}
