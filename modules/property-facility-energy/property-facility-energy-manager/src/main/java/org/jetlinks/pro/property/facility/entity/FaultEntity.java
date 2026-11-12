package org.jetlinks.pro.property.facility.entity;

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

/** Fault持久化记录；来源、权限、版本及生命周期由对应领域服务维护。 */
@Getter
@Setter
@EnableEntityEvent
@Asset(type = "facilityFault")
@Table(
  name = "fe_fault",
  indexes = @Index(
    name = "idx_fe_fault_scope",
    columnList = "tenantId,applicationId,projectId,state",
    unique = false
  )
)
public class FaultEntity extends FacilityEntity {

  @Column(length = 256)
  private String name;

  @Column(length = 256)
  private String deviceId;

  @Column(length = 256)
  private String deviceName;

  @Column(length = 256)
  private String sourceType;

  @Column(length = 256)
  private String sourceId;

  @Column(length = 4000)
  private String description;

  @Column(length = 256)
  private String state;

  @Column(length = 256)
  private String assigneeId;

  @Column(length = 128)
  private String assigneeName;

  @Column(length = 64)
  private String reviewerId;

  @Column(length = 128)
  private String reviewerName;

  @Column(length = 32)
  private String priority;

  @Column(length = 64)
  private String category;

  @Column
  private Long deadline;

  @Column
  private Long claimedTime;

  @Column
  private Long startedTime;

  @Column
  private Long submittedTime;

  @Column(length = 4000)
  private String closeReason;

  @Column
  @JsonCodec
  @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
  private List<String> resolutionAttachments = new ArrayList<>();

  @Column(length = 4000)
  private String resolution;

  @Column
  @JsonCodec
  @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
  private List<String> attachments = new ArrayList<>();

  @Column
  private Long completedTime;
}
