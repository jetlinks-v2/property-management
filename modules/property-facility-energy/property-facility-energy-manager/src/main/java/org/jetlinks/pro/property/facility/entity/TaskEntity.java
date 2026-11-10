package org.jetlinks.pro.property.facility.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.sql.JDBCType;
import java.util.*;
import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.*;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.jetlinks.pro.assets.annotation.Asset;
import org.jetlinks.pro.property.facility.model.*;

/** Task持久化记录；来源、权限、版本及生命周期由对应领域服务维护。 */
@Getter
@Setter
@EnableEntityEvent
@Asset(type = "facilityTask")
@Table(
  name = "fe_task",
  indexes = { @Index(name = "idx_fe_task_parent", columnList = "parentTaskId,deviceId"), @Index(
    name = "idx_fe_task_scope",
    columnList = "tenantId,applicationId,planId,scheduledTime,deviceId",
    unique = true
  ) }
)
public class TaskEntity extends FacilityEntity {

  /** 非空表示整项任务下的设备明细；旧单设备工单保持为空。 */
  @Column(length = 64)
  private String parentTaskId;

  @Column
  private Integer deviceCount;

  @Column
  private Integer submittedDeviceCount;

  /** 生成时的设备组归属快照，扫码不随设备组后续编辑漂移。 */
  @Column
  @JsonCodec
  @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
  private List<String> groupIds = new ArrayList<>();

  /** 离线下载按当前授权携带整项任务的设备快照，不额外持久化。 */
  @Transient
  private List<TaskEntity> deviceTasks;

  @Column(length = 256)
  private String name;

  @Column(length = 256)
  private String planId;

  @Column(length = 256)
  private String kind;

  @Column(length = 256)
  private String deviceId;

  @Column(length = 256)
  private String deviceName;

  @Column(length = 256)
  private String standardId;

  @Column(length = 256)
  private String state;

  @Column(length = 256)
  @JsonIgnore
  private String mode;

  @Column(length = 256)
  private String assigneeId;

  @Column(length = 256)
  private String assigneeName;

  @Column(length = 256)
  @JsonIgnore
  private String followUserId;

  @Column(length = 256)
  private String reviewerId;

  @Column(length = 256)
  private String reviewerName;

  @Column(length = 256)
  @JsonIgnore
  private String externalOrganizationId;

  @Column
  private Long scheduledTime;

  @Column
  private Long deadline;

  /** 生单时固定预警时点，修改周期规则不改变正在执行的任务。 */
  @Column
  private Long warningTime;

  @Column
  private Long completedTime;

  @Column
  @JsonCodec
  @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
  private List<CheckItem> standardSnapshot = new ArrayList<>();

  @Column
  @JsonCodec
  @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
  private List<CheckResult> results = new ArrayList<>();

  @Column
  @JsonCodec
  @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
  @JsonIgnore
  private List<CheckResult> followResults = new ArrayList<>();

  @Column(length = 4000)
  private String instructionSnapshot;

  @Column
  @JsonCodec
  @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
  private List<String> instructionAttachments = new ArrayList<>();

  @Column
  @JsonCodec
  @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
  private Map<String, Object> deviceSnapshot = new LinkedHashMap<>();

  @Column(length = 256)
  private String reviewComment;

  @Column(length = 256)
  private String submissionId;

  @Column(length = 256)
  @JsonIgnore
  private String followSubmissionId;
}
