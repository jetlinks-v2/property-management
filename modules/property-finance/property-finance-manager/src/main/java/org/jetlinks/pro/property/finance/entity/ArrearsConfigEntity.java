package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 欠费通知等级配置主档；规则变更与主档版本在同一事务中提交。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_arrears_config",
        indexes = @Index(name = "uk_pm_fin_arrears_name", columnList = "nameKey", unique = true))
public class ArrearsConfigEntity extends FinanceEntity {
    @Column(length = 100, nullable = false)
    private String name;

    @Column(length = 100)
    private String nameKey;

    @Column(length = 250)
    private String remark;
}
