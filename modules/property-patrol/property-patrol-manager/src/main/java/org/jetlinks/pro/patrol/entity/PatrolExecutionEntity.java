package org.jetlinks.pro.patrol.entity;

import jakarta.validation.constraints.NotBlank;

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
            @Index(name = "pt_exec_user", columnList = "userId,receiveTime")
        })
public class PatrolExecutionEntity extends GenericEntity<String> {
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

    public record Answer(@NotBlank String itemId, @NotBlank String value) {}
}
