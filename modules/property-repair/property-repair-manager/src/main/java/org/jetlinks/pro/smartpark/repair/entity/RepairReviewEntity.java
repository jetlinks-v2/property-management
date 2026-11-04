package org.jetlinks.pro.smartpark.repair.entity;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import javax.persistence.Column;
import javax.persistence.Table;
import javax.persistence.Index;
import java.sql.JDBCType;
import java.util.Map;
import java.util.List;

/** 审核申请快照；延期、非关、降级、归档、废弃各自保留类型及审核角色。 */
@Getter @Setter
@Table(name="sp_repair_review",indexes={@Index(name="idx_repair_review_order",columnList="orderId,status"),
    @Index(name="idx_repair_review_app",columnList="applicationId,createTime")})
public class RepairReviewEntity extends GenericEntity<String> {
    @Column(length=64,nullable=false) private String orderId;
    @Column(length=64,nullable=false) private String tenantId;
    @Column(length=64,nullable=false) private String applicationId;
    @Column(length=32,nullable=false) private String kind;
    @Column(length=64,nullable=false) private String roleId;
    @Column private Integer status;
    @Column(length=64) private String applicantId;
    @Column(length=128) private String applicantName;
    @Column(length=2000) private String reason;
    @Column private Long delayUntil;
    @Column private Long createTime;
    @Column private Long reviewTime;
    @Column(length=64) private String reviewerId;
    @Column(length=128) private String reviewerName;
    @Column(length=2000) private String opinion;
    @Column(length=2000) private String refuseReason;
    @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class)
    private Map<String,Object> targetLevel;
    @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class)
    private List<String> attachmentIds;
}
