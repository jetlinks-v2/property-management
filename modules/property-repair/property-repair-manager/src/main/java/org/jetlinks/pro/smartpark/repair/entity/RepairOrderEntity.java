package org.jetlinks.pro.smartpark.repair.entity;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.Column;
import javax.persistence.Table;
import javax.persistence.Index;
import org.hswebframework.web.api.crud.entity.ExtendableEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.jetlinks.pro.assets.annotation.Asset;
import java.sql.JDBCType;
import java.util.List;
import java.util.Map;
/** 工单归属于报修业务项目组织，不能用创建人的组织替代。 */
@Getter @Setter @EnableEntityEvent @Asset(type="repairOrder")
@Table(name="sp_repair_order",indexes={
 @Index(name="idx_repair_app_created",columnList="applicationId,createTime"),
 @Index(name="idx_repair_org_status",columnList="organizationId,status"),
 @Index(name="idx_repair_assignee",columnList="assignedUserId,status")})
public class RepairOrderEntity extends ExtendableEntity<String> implements org.hswebframework.web.api.crud.entity.RecordCreationEntity {
 @Column(length=64,updatable=false) private String creatorId;
 @Column(length=128,updatable=false) private String creatorName;
 // 登记时读取认证用户资料的快照，与报事人联系电话分开；匿名入口使用提交人的联系方式。
 @Column(length=64,updatable=false) private String registerPhone;
 @Column(length=64,nullable=false,updatable=false) private String applicationId;
 @Column(length=64,nullable=false,updatable=false) private String businessProjectId;
 @Column(length=64,nullable=false,updatable=false) private String organizationId;
 @Column(length=128) private String businessProjectName;
 @Column(length=64,updatable=false) private String number;
 @Column(length=128) private String title;
 @Column(length=4000) private String description;
 @Column(length=64) private String categoryId;
 @Column(length=128) private String categoryName;
 @Column(length=64) private String channelId;
 @Column(length=128) private String channelName;
 @Column(length=64) private String source;
 @Column private Integer reportSource;
 @Column private Integer positionType;
 @Column private Integer urgency;
 @Column(length=512) private String spaceName;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class)
 private List<String> labelIds;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class)
 private List<String> labelNames;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class)
 private List<String> descriptionChoiceIds;
 @Column(length=512) private String location;
 @Column(length=64) private String spaceId;
 @Column(length=32) private String intakeIdentity;
 @Column(length=64) private String qrConfigurationId;
 @Column(length=64) private String deviceId;
 @Column(length=128) private String deviceName;
 @Column(length=64) private String deviceProductId;
 @Column(length=128) private String deviceProductName;
 @Column(length=64) private String contactName;
 @Column(length=64) private String contactPhone;
 @Column(length=64,updatable=false) private String reporterId;
 @Column(length=128,updatable=false) private String reporterName;
 @Column(length=64) private String assignedUserId;
 @Column(length=128) private String assignedUserName;
 @Column(length=64) private String candidateRoleId;
 @Column(length=32) private String candidateSource;
 @Column private Integer status;
 @Column private Long version;
 @Column private Integer flowVersion;
 @Column private Long acceptTime;
 @Column private Long finishDeadline;
 @Column private Long finishTime;
 @Column private Long archiveTime;
 @Column private Boolean timedOut;
 @Column(length=64) private String levelId;
 @Column(length=128) private String levelName;
 @Column private Integer levelCoefficient;
 @Column private Boolean levelAuditRequired;
 @Column(length=64) private String levelAuditRoleId;
 @Column(length=16) private String levelColor;
 @Column private Boolean levelThirdParty;
 @Column private Integer transferCount;
 @Column private Integer delayCount;
 @Column private Boolean staffEvaluated;
 @Column private Boolean ownerEvaluated;
 @Column private Boolean paid;
 @Column private Boolean signatureRequested;
 @Column private Boolean signed;
 @Column(precision=16,scale=2) private java.math.BigDecimal materialAmount;
 @Column(precision=16,scale=2) private java.math.BigDecimal artificialAmount;
 @Column(precision=16,scale=2) private java.math.BigDecimal totalAmount;
 @Column(precision=20,scale=6) private java.math.BigDecimal personAmount;
 @Column(precision=20,scale=6) private java.math.BigDecimal providerAmount;
 @Column(precision=20,scale=6) private java.math.BigDecimal propertyAmount;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class)
 private Map<String,Object> commissionSnapshot;
 @Column(length=64) private String signatureFileId;
 @Column(length=128) private String signatureOwnerName;
 @Column(length=64) private String signatureOwnerPhone;
 @Column(length=64) private String signatureRecorderId;
 @Column(length=128) private String signatureRecorderName;
 @Column private Long signatureTime;
 @Column(length=128) private String evaluationOwnerName;
 @Column(length=64) private String evaluationOwnerPhone;
 @Column(length=64) private String ownerEvaluationRecorderId;
 @Column(length=128) private String ownerEvaluationRecorderName;
 @Column private Long ownerEvaluationTime;
 @Column private Long staffSatisfaction;
 @Column private Long ownerSatisfaction;
 @Column(length=64) private String staffSatisfactionId;
 @Column(length=128) private String staffSatisfactionName;
 @Column(length=64) private String ownerSatisfactionId;
 @Column(length=128) private String ownerSatisfactionName;
 @Column private Long abandonTime;
 @Column(length=2000) private String abandonReason;
 @Column private Long createTime;
 @Column private Long modifyTime;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class)
 private List<String> attachmentIds;
}
