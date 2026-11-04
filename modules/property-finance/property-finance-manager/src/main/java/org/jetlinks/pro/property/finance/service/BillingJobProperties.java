package org.jetlinks.pro.property.finance.service;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalTime;
import java.time.ZoneId;

/** 与基础数据每小时同步独立；关期沿旧系统默认关闭，业务时区必须与计费日期一致。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "property.finance.billing-job")
public class BillingJobProperties {
    private boolean enabled = true;
    private boolean closeEnabled = false;
    private String zone = ZoneId.systemDefault().getId();
    private LocalTime generateAt = LocalTime.of(0, 0);
    private LocalTime accrueAt = LocalTime.of(0, 10);
    private LocalTime closeAt = LocalTime.of(0, 20);
    private LocalTime lateFeeAt = LocalTime.of(0, 30);
}
