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
/** 品质WorkOrder持久化模型；归属与状态转换由对应应用服务维护。 */
@Getter @Setter @EnableEntityEvent
@Asset(type="qualityWorkOrder")
@Table(name="pq_work_order",indexes={@Index(name="idx_pq_order_scope",columnList="applicationId,projectId,workOrderStatus,deleted"),@Index(name="uk_pq_order_batch",columnList="taskId,scheduledTime",unique=true)})
public class QualityWorkOrderEntity extends QualityEntity {
 @Column(length=128) private String taskId;
 @Column(length=256) private String taskName;
 @Column private Integer taskLevel;
 @Column(length=128) private String taskCode;
 @Column(length=128) private String templateId;
 @Column(length=256) private String templateName;
 @Column private Integer taskType;
 @Column private Long scheduledTime;
 @Column private Long cycleStartTime;
 @Column private Long cycleEndTime;
 @Column private Long reportDeadline;
 @Column private Integer workOrderStatus;
 @Column private Integer recordCount;
 @Column private Integer originalTotalScore;
 @Column private Integer totalScore;
 @Column(length=256) private String evaluationName;
 @Column(length=128) private String inspectorId;
 @Column(length=256) private String inspectorName;
 @Column private Long inspectionTime;
 @Column private Long completeTime;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class) private List<String> inspectorIds;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class) private List<String> reviewerIds;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class) private List<Map<String,Object>> lineWeights;
}
