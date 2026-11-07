package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.*;

/** 自动结转配置审计，创建、编辑、启停及逻辑删除均与配置同事务；历史仅供查询。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_carry_plan_log",
        indexes =
                @Index(
                        name = "idx_pm_fin_carry_plan_log",
                        columnList = "projectId,planId,createTime,id"))
public class CarryForwardPlanLogEntity extends GenericEntity<String> {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String planId;

    @Column(length = 16, nullable = false)
    private String operation;

    @Column(length = 20000)
    private String beforeJson;

    @Column(length = 20000, nullable = false)
    private String afterJson;

    @Column(length = 64, nullable = false)
    private String operatorId;

    @Column(length = 128, nullable = false)
    private String operatorName;

    @Column(nullable = false)
    private Long createTime;
}
