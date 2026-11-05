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

/** Replacement持久化记录；来源、权限、版本及生命周期由对应领域服务维护。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
  name = "fe_replacement",
  indexes = @Index(
    name = "idx_fe_replacement_scope",
    columnList = "tenantId,applicationId,oldDeviceId,createTime",
    unique = false
  )
)
public class ReplacementEntity extends FacilityEntity {

  @Column(length = 256)
  private String oldDeviceId;

  @Column(length = 256)
  private String newDeviceId;

  @Column
  private Long effectiveTime;

  @Column
  private BigDecimal finalReading;

  @Column
  private BigDecimal initialReading;

  @Column(length = 4000)
  private String reason;

  @Column(length = 256)
  private String state;

  @Column(length = 256)
  private String reviewerId;
}
