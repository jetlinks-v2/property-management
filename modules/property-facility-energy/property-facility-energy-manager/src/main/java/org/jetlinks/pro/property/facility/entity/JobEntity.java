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

/** Job持久化记录；来源、权限、版本及生命周期由对应领域服务维护。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
  name = "fe_job",
  indexes = @Index(
    name = "idx_fe_job_scope",
    columnList = "tenantId,applicationId,projectId,kind",
    unique = true
  )
)
public class JobEntity extends FacilityEntity {

  @Column(length = 256)
  private String name;

  @Column(length = 256)
  private String kind;

  @Column
  private Boolean enabled = false;

  @Column(length = 256)
  private String state;

  @Column
  private Long nextRunTime;

  @Column
  private Long lastSuccessTime;

  @Column
  private Long lastStartTime;

  @Column
  private Long leaseUntil;

  @Column
  private Integer processed;

  @Column(length = 4000)
  private String failureReason;

  @Column
  @JsonCodec
  @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
  private List<String> authorizedDeviceIds = new ArrayList<>();
}
