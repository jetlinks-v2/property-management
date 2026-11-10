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

/** Plan持久化记录；来源、权限、版本及生命周期由对应领域服务维护。 */
@Getter
@Setter
@EnableEntityEvent
@Asset(type = "facilityPlan")
@Table(
  name = "fe_plan",
  indexes = @Index(
    name = "idx_fe_plan_scope",
    columnList = "tenantId,applicationId,nextRunTime",
    unique = false
  )
)
public class PlanEntity extends FacilityEntity {

  @Column(length = 256)
  private String name;

  @Column(length = 256)
  private String kind;

  @Column(length = 256)
  private String standardId;

  @Column(length = 256)
  private String cycleId;

  @Column(length = 256)
  private String cycleName;
  @Column(length = 256)
  private String standardName;
  @Column(length = 128)
  private String reviewerName;

  @Column
  @JsonCodec
  @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
  private List<String> deviceIds = new ArrayList<>();

  @Column
  @JsonCodec
  @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
  private List<String> groupIds = new ArrayList<>();

  @Column(length = 256)
  @JsonIgnore
  private String assigneeId;

  @Column(length = 256)
  @JsonIgnore
  private String followUserId;

  @Column(length = 256)
  @JsonIgnore
  private String externalOrganizationId;

  @Column(length = 256)
  private String reviewerId;

  @Column(length = 256)
  @JsonIgnore
  private String mode;

  @Column(length = 256)
  private String intervalUnit;

  @Column
  private Integer intervalValue;

  @Column
  private Long startTime;

  @Column
  private Long endTime;

  @Column
  private Long nextRunTime;

  @Column
  private Integer deadlineHours;

  @Column
  private Boolean enabled = false;
}
