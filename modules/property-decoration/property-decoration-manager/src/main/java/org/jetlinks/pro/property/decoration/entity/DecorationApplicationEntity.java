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

/** 装修自有数据；仅通过受控业务服务维护，不暴露通用写入和物理删除。 */
@Getter @Setter @EnableEntityEvent
@Table(name="pd_application",indexes={ @Index(name="uk_pd_active_house", columnList="activeHouseKey", unique=true),
    @Index(name="idx_pd_application_scope", columnList="applicationId,projectId,status,createTime"),
    @Index(name="idx_pd_application_owner", columnList="ownerUserId,createTime"),
    @Index(name="idx_pd_application_inspection", columnList="nextInspectionTime"),
    @Index(name="idx_pd_application_latest", columnList="applicationId,tenantId,projectId,spaceId,createTime,id") })
public class DecorationApplicationEntity extends GenericEntity<String> {

    @Column(length=64, nullable=false, updatable=false) private String applicationId;
    @Column(length=64, nullable=false, updatable=false) private String tenantId;
    @Column(length=64, nullable=false, updatable=false) private String projectId;
    @Column(length=128, updatable=false) private String projectName;
    @Column(length=64, nullable=false, updatable=false) private String spaceId;
    @Column(length=512, updatable=false) private String spaceName;
    @Column(length=64, nullable=false, updatable=false) private String customerId;
    @Column(length=64, nullable=false, updatable=false) private String ownerUserId;
    @Column(length=128, updatable=false) private String ownerName;
    @Column(length=64) private String contactPhone;
    @Column(length=64, updatable=false) private String relationshipId;
    @Column(length=64) private String activeHouseKey;
    @Column(length=64, nullable=false, updatable=false) private String number;
    @Column(length=16, nullable=false, updatable=false) private String source;
    @Column(length=64, updatable=false) private String requestDigest;
    @Column(length=64, updatable=false) private String creatorId;
    @Column(length=128, updatable=false) private String creatorName;
    @Column(length=128) private String constructionUnit;
    @Column(length=128) private String constructionManager;
    @Column(length=64) private String managerPhone;
    @Column(length=64) private String houseType;
    @Column(length=64) private String decorationType;
    @Column private Long startTime;
    @Column private Long completionTime;
    @Column(length=64) private String status;
    @Column private Long version;
    @Column private Long createTime;
    @Column private Long modifyTime;
    /** 内部生成游标不改变人工动作版本；生成与人工动作共用申请行锁。 */
    @Column private Long nextInspectionTime;
    @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class)
    private DecorationModels.TaskDetails inspectionPlan;
    @Column private Long actualEndTime;
    @Column(length=2000) private String endReason;
    @Column private Boolean deleted;
    @Column private Integer depositReviewStatus;
    @Column(precision=14,scale=2) private java.math.BigDecimal approvedDeposit;
    @Column(length=64) private String depositReviewerId;
    @Column(length=128) private String depositReviewerName;
    @Column private Long depositReviewTime;
    @Column(length=2000) private String depositReviewRemark;
    @Column private Integer paymentStatus;
    @Column(length=128) private String paymentReference;
    @Column(precision=14,scale=2) private java.math.BigDecimal paidAmount;
    @Column private Long paidTime;
    @Column private Integer refundStatus;
    @Column(length=128) private String refundReference;
    @Column(precision=14,scale=2) private java.math.BigDecimal refundedAmount;
    @Column private Long refundTime;
    @Column private Integer acceptanceRound;
    @Column private Integer acceptanceType;
    @Column private Long acceptanceDate;
    @Column private Long acceptanceRequestTime;
    @Column(length=64) private String acceptanceRequesterId;
    @Column(length=128) private String acceptanceRequesterName;
    @Column(length=16) private String acceptanceSource;
    @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class)
    private DecorationModels.ApplicationDetails details;
    @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class)
    private DecorationModels.Permit permit;

}
