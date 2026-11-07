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
/** 品质Task持久化模型；归属与状态转换由对应应用服务维护。 */
@Getter @Setter @EnableEntityEvent
@Asset(type="qualityTask")
@Table(name="pq_task",indexes={@Index(name="idx_pq_task_scope",columnList="applicationId,projectId,taskStatus,deleted")})
public class QualityTaskEntity extends QualityEntity {
 @Column(length=256) private String taskName;
 @Column private Integer taskType;
 @Column private Integer taskLevel;
 @Column(length=128) private String templateId;
 @Column(length=256) private String templateName;
 @Column(length=128) private String checkRole;
 @Column(length=256) private String checkRoleName;
 @Column private Integer inspectionFrequency;
 @Column private Integer operationFrequency;
 @Column private Long startTime;
 @Column private Long endTime;
 @Column private Long firstGenerateTime;
 @Column private Long reportDeadline;
 @Column private Integer reportDeadlineValue;
 @Column(length=128) private String reportDeadlineUnit;
 @Column private Integer intervalValue;
 @Column private Integer intervalUnit;
 @Column private Integer taskStatus;
 @Column private Long issueTime;
 @Column private Long closeTime;
 @Column private Long completeTime;
 @Column(length=8000) private String remark;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class) private Map<String,Object> scheduleRules;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class) private List<Map<String,Object>> configItems;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class) private List<Map<String,Object>> lineAssignees;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class) private List<Map<String,Object>> templateSnapshot;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class) private List<Map<String,Object>> lineWeights;
}
