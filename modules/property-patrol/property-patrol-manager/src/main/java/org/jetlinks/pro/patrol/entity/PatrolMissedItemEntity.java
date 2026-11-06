package org.jetlinks.pro.patrol.entity;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.EnumCodec;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 截止后每个缺失次数一条明细；当前申报状态可变，历次操作另存追加事件。 */
@Getter
@Setter
@Table(name = "pt_missed_item", indexes = {
    @Index(name = "pt_missed_task_no", columnList = "taskId,occurrenceNo", unique = true),
    @Index(name = "pt_missed_group", columnList = "taskGroupId"),
    @Index(name = "pt_missed_plan", columnList = "planId")
})
public class PatrolMissedItemEntity extends GenericEntity<String> {
    @Column(length = 64, nullable = false, updatable = false) private String taskId;
    @Column(length = 64, updatable = false) private String taskGroupId;
    @Column(length = 64, nullable = false, updatable = false) private String planId;
    @Column(nullable = false, updatable = false) private int occurrenceNo;
    @Column(nullable = false, updatable = false) private Long createTime;
    @Column(nullable = false) private int revision;
    @Column(length = 16, nullable = false) @EnumCodec @ColumnType(javaType = String.class)
    private Status status = Status.none;
    @Column(length = 64) private String applicationId;
    @Column(length = 64) private String applicantId;
    @Column(length = 128) private String applicantName;
    @Column(length = 500) private String reason;
    @Column private Long applyTime;
    @Column(length = 64) private String auditorId;
    @Column(length = 128) private String auditorName;
    @Column(length = 500) private String auditRemark;
    @Column private Long auditTime;
    private transient String pointName;
    private transient String roleName;
    private transient Long periodStartTime;
    private transient Long periodEndTime;
    public enum Status { none, pending, approved, rejected, withdrawn }
}
