package org.jetlinks.pro.patrol.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.sql.JDBCType;
import java.util.List;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 执行事实只追加。ID 按执行者+客户端请求号生成，重试不能增加计数。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pt_execution",
        indexes = {
            @Index(name = "pt_exec_task", columnList = "taskId,receiveTime"),
            @Index(name = "pt_exec_user", columnList = "userId,receiveTime"),
            @Index(name = "pt_exec_group_time", columnList = "taskGroupId,receiveTime")
        })
public class PatrolExecutionEntity extends GenericEntity<String> {
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
    private String taskId;

    @Column(length = 64, nullable = false, updatable = false)
    private String planId;

    @Column(length = 64, nullable = false, updatable = false)
    private String pointId;

    @Column(length = 128, updatable = false)
    private String pointName;

    @Column(length = 64, nullable = false, updatable = false)
    private String userId;

    @Column(length = 128, updatable = false)
    private String userName;

    @Column(length = 64, nullable = false, updatable = false)
    private String requestId;

    @Column(nullable = false, updatable = false)
    private Long receiveTime;

    @Column(length = 1000, updatable = false)
    private String remark;

    @Column
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    private List<Answer> answers;

    @Column
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    private List<PatrolStandardEntity.CheckItem> standardSnapshot;

    @Column(length = 20, updatable = false)
    @org.hswebframework.ezorm.rdb.mapping.annotation.EnumCodec
    @ColumnType(javaType = String.class)
    private CheckResult result;

    @Column
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    private List<ItemResult> itemResults;

    /** 未配置判定规则、未作人工判断和已部署历史记录不得自动视为合格。 */
    public enum CheckResult { pass, fail, not_evaluated }

    public record ItemResult(String itemId, CheckResult result) {}

    public record Answer(
            @NotBlank @Size(max = 64) String itemId,
            @Size(max = 2000) String value,
            @Size(max = 64) String attachmentId) {
        public Answer(String itemId, String value) { this(itemId, value, null); }
    }
}
