package org.jetlinks.pro.property.quality.entity;
import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.util.*;
import java.math.BigDecimal;
import java.sql.JDBCType;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.jetlinks.pro.assets.annotation.Asset;
/** 品质Record持久化模型；归属与状态转换由对应应用服务维护。 */
@Getter @Setter @EnableEntityEvent
@Table(name="pq_record",indexes={@Index(name="idx_pq_record_order",columnList="workOrderId,recordStatus"),@Index(name="idx_pq_record_executor",columnList="applicationId,executorId,recordStatus")})
public class QualityRecordEntity extends QualityEntity {
 @Column(length=128) private String workOrderId;
 @Column(length=128) private String taskId;
 @Column(length=128) private String subjectParentId;
 @Column(length=256) private String subjectParentName;
 @Column(length=128) private String subjectId;
 @Column(length=256) private String subjectName;
 @Column(length=128) private String checkContentId;
 @Column(length=256) private String checkContentName;
 @Column(length=8000) private String checkRequirement;
 @Column(length=128) private String photoRequirement;
 @Column private Integer originalScore;
 @Column private Integer score;
 @Column(length=8000) private String deductionStandard;
 @Column private BigDecimal deductionScore;
 @Column private Integer sortOrder;
 @Column private Integer isQualified;
 @Column(length=8000) private String checkResult;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class) private List<String> photoIds;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class) private List<String> rectificationPhotoIds;
 @Column(length=128) private String executorId;
 @Column(length=256) private String executorName;
 @Column private Integer recordStatus;
 @Column private Long reportDeadline;
 @Column private Long completeTime;
 @Column(length=128) private String repairApplicationId;
 @Column(length=128) private String repairOrderId;
}
