package org.jetlinks.pro.patrol.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.api.crud.entity.GenericEntity;

import javax.persistence.Column;
import javax.persistence.Table;
import javax.persistence.Index;

/** 计划指派+归属日期的逻辑任务组及生成封印；ID 同时写入全部时段，生成后不随规则编辑重建。 */
@Getter
@Setter
@Table(name = "pt_generation", indexes = @Index(name = "pt_group_point_date", columnList = "pointId,belongDate"))
public class PatrolGenerationEntity extends GenericEntity<String> {
    @Column(length = 128, updatable = false)
    private String name;
    @Column(length = 64, updatable = false)
    private String pointId;
    @Column(length = 128, updatable = false)
    private String pointName;
    @Column(length = 64, updatable = false)
    private String postId;
    @Column(length = 128, updatable = false)
    private String postName;
    @Column(length = 10, updatable = false)
    private String belongDate;
    @Column(length = 64, updatable = false)
    private String ruleVersion;
    @Column(updatable = false)
    private Integer periodCount;
    @Column(updatable = false)
    private Integer requiredCount;
    @Column(updatable = false)
    private Long startTime;
    @Column(updatable = false)
    private Long endTime;

    @Column(length = 64, nullable = false, updatable = false)
    private String planId;

    @Column(length = 10, nullable = false, updatable = false)
    private String businessDate;
}
