package org.jetlinks.pro.property.finance.service;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 只从受控部署配置读取来源，默认不连接；不得由同步请求传入远程URL。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "property.finance.contract-source")
public class ContractSourceProperties {
    private String url;
}
