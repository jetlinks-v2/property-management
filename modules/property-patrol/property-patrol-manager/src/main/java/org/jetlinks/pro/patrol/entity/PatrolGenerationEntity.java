package org.jetlinks.pro.patrol.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.api.crud.entity.GenericEntity;

import javax.persistence.Column;
import javax.persistence.Table;
import javax.persistence.Index;
import javax.persistence.Transient;

/** 计划指派+归属日期的逻辑任务组及生成封印；ID 同时写入全部时段，生成后不随规则编辑重建。 */
@Getter
@Setter
@Table(name = "pt_generation", indexes = @Index(name = "pt_group_point_date", columnList = "pointId,belongDate"))
public class PatrolGenerationEntity extends GenericEntity<String> {
    /** 生成时的业务项目快照，不随员工调岗或点位名称变化回写。 */
    @Column(length = 64)
    private String projectId;
    @Column(length = 128)
    private String projectName;

    /** 查询投影，取自首个时段的生成快照；不从当前点位/等级回填历史。 */
    @Transient private String categoryId;
    @Transient private String categoryName;
    @Transient private String levelId;
    @Transient private String levelName;
    /** 当前真实时段进度；不保存第二份可漂移的计数器。 */
    @Transient private int completedCount;
    @Transient private Long lastExecutionTime;

    @Column(length = 128, updatable = false)
    private String name;
    @Column(length = 64, updatable = false)
    private String pointId;
    @Column(length = 128, updatable = false)
    private String pointName;
    @Column(length = 64, updatable = false)
    private String roleId;
    @Column(length = 128, updatable = false)
    private String roleName;
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
