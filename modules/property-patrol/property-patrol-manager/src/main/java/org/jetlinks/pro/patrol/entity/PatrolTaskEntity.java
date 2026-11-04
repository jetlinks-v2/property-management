package org.jetlinks.pro.patrol.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.EnumCodec;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.jetlinks.pro.patrol.domain.ExecutionPolicy;

import java.sql.JDBCType;
import java.util.List;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 一个计划的一个逻辑时段；主键由计划+时段稳定生成，不包含规则版本，避免编辑后重复应巡。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pt_task",
        indexes = {
            @Index(name = "pt_task_plan_time", columnList = "planId,startTime", unique = true),
            @Index(name = "pt_task_end", columnList = "endTime"),
            @Index(name = "pt_task_missed_scan", columnList = "missedInitialized,endTime"),
            @Index(name = "pt_task_group_period", columnList = "taskGroupId,periodNo", unique = true),
            @Index(name = "pt_task_date_plan", columnList = "businessDate,planId")
        })
public class PatrolTaskEntity extends GenericEntity<String> {
    /** 生成时的业务项目快照，不随员工调岗或点位名称变化回写。 */
    @Column(length = 64)
    private String projectId;
    @Column(length = 128)
    private String projectName;

    /** 生成时确定的逻辑任务组；不得由查询端按点位和日期重新推算。 */
    @Column(length = 64, updatable = false)
    private String taskGroupId;

    /** 规则归属日，跨天时段仍属于开始生成规则的业务日期。 */
    @Column(length = 10, updatable = false)
    private String belongDate;

    @Column(updatable = false)
    private Integer periodNo;

    @Column(length = 64, updatable = false)
    private String ruleVersion;

    @Column(length = 64, nullable = false, updatable = false)
    private String planId;

    @Column(length = 128, nullable = false, updatable = false)
    private String name;

    @Column(length = 64, nullable = false, updatable = false)
    private String pointId;

    @Column(length = 128, updatable = false)
    private String pointName;

    @Column(length = 64, updatable = false)
    private String categoryId;

    @Column(length = 128, updatable = false)
    private String categoryName;

    @Column(length = 64, updatable = false)
    private String levelId;

    @Column(length = 128, updatable = false)
    private String levelName;

    @Column(length = 64, nullable = false, updatable = false)
    private String postId;

    @Column(length = 128, updatable = false)
    private String postName;

    @Column(length = 10, nullable = false, updatable = false)
    private String businessDate;

    @Column(nullable = false, updatable = false)
    private Long startTime;

    @Column(nullable = false, updatable = false)
    private Long endTime;

    @Column(nullable = false, updatable = false)
    private int requiredCount;

    @Column(nullable = false)
    private int completedCount;

    @Column private Long lastExecutionTime;

    /** 截止后漏检序号只生成一次；null 包含已部署旧任务，true 表示已封印，不随审核重建。 */
    @Column private Boolean missedInitialized;

    @Column(nullable = false, updatable = false)
    private int cooldownMinutes;

    @Column(length = 16, nullable = false, updatable = false)
    @EnumCodec
    @ColumnType(javaType = String.class)
    private ExecutionPolicy.RosterPolicy rosterPolicy;

    @Column
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    private List<PatrolStandardEntity.CheckItem> standardSnapshot;

    @Column(nullable = false, updatable = false)
    private Long createTime;
}
