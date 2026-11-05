package org.jetlinks.pro.property.decoration.entity;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.Column;
import javax.persistence.Table;
import javax.persistence.Index;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import java.sql.JDBCType;
import org.jetlinks.pro.property.decoration.model.DecorationModels;

/**
 * 聚合事务创建的巡检或验收任务；保存当时的装修与验收申请快照，供历史列表和查询使用。
 * 任务办理仅更新结果与版本，不以当前装修申请覆盖历史人员、施工信息或申请时间。
 */
@Getter @Setter @EnableEntityEvent
@Table(name="pd_task",indexes={ @Index(name="idx_pd_task_app",columnList="decorationId,kind,round"),
    @Index(name="idx_pd_task_assignee",columnList="applicationId,projectId,assigneeId,status") })
public class DecorationTaskEntity extends GenericEntity<String> {

    @Column(length=64, nullable=false, updatable=false) private String applicationId;
    @Column(length=64, nullable=false, updatable=false) private String tenantId;
    @Column(length=64, nullable=false, updatable=false) private String projectId;
    @Column(length=128, updatable=false) private String projectName;
    @Column(length=64, updatable=false) private String number;
    @Column(length=512, updatable=false) private String spaceName;
    @Column(length=128, updatable=false) private String ownerName;
    @Column(length=64, updatable=false) private String contactPhone;
    @Column(length=128, updatable=false) private String constructionUnit;
    @Column(length=128, updatable=false) private String constructionManager;
    @Column(length=64, updatable=false) private String managerPhone;
    @Column(length=64, updatable=false) private String houseType;
    @Column(length=64, updatable=false) private String decorationType;
    @Column(updatable=false) private Long applicationTime;
    @Column(updatable=false) private Long decorationStartTime;
    @Column(updatable=false) private Long completionTime;
    @Column(length=64, updatable=false) private String creatorId;
    @Column(length=128, updatable=false) private String creatorName;
    @Column(updatable=false) private Long acceptanceDate;
    @Column(updatable=false) private Long acceptanceRequestTime;
    @Column(length=64, updatable=false) private String acceptanceRequesterId;
    @Column(length=128, updatable=false) private String acceptanceRequesterName;
    @Column(length=16, updatable=false) private String acceptanceSource;
    @Column(length=64, updatable=false) private String sourceTaskId;
    @Column(length=64, nullable=false, updatable=false) private String decorationId;
    @Column(length=64, nullable=false, updatable=false) private String kind;
    @Column(length=64, updatable=false) private String parentId;
    @Column private Integer round;
    @Column(length=64) private String assigneeId;
    @Column(length=128) private String assigneeName;
    @Column(length=32) private String status;
    @Column private Long version;
    @Column private Long createTime;
    @Column private Long modifyTime;
    @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class)
    private DecorationModels.TaskDetails details;

}
