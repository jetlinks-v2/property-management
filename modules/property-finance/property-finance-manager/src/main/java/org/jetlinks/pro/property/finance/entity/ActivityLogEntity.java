package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.api.crud.entity.GenericEntity;

import javax.persistence.*;

/** 活动配置审计，不开放通用增删改接口。 */
@Getter
@Setter
@Table(
        name = "pm_fin_activity_log",
        indexes =
                @Index(
                        name = "idx_pm_fin_activity_log",
                        columnList = "projectId,activityId,createTime"))
public class ActivityLogEntity extends GenericEntity<String> {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String activityId;

    @Column(length = 20, nullable = false)
    private String operation;

    @Column(length = 1000000)
    private String beforeJson;

    @Column(length = 1000000, nullable = false)
    private String afterJson;

    @Column(length = 64, nullable = false)
    private String operatorId;

    @Column(length = 128)
    private String operatorName;

    @Column(nullable = false)
    private Long createTime;
}
