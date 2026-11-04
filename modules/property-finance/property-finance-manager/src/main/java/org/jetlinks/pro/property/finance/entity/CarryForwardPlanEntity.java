package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.*;

/** 项目自动结转计划。配置和版本只从持久记录恢复，执行器不能接受调用方替换减免规则。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_carry_plan",
        indexes =
                @Index(name = "idx_pm_fin_carry_project", columnList = "projectId,deleted,enabled"))
public class CarryForwardPlanEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 100, nullable = false)
    private String name;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(length = 16000, nullable = false)
    private String configJson;
}
