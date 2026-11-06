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

/** Reading持久化记录；来源、权限、版本及生命周期由对应领域服务维护。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
  name = "fe_reading",
  indexes = @Index(
    name = "idx_fe_reading_scope",
    columnList = "tenantId,applicationId,deviceId,propertyId,observedTime,source",
    unique = false
  )
)
public class ReadingEntity extends FacilityEntity {

  @Column(length = 256)
  private String deviceId;

  @Column(length = 256)
  private String mappingId;

  @Column
  private Long mappingVersion;

  @Column(length = 256)
  private String energyType;

  @Column(length = 256)
  private String propertyId;

  @Column(length = 256)
  private String metricKind;

  @Column(length = 256)
  private String unit;

  @Column(precision = 44, scale = 12)
  private BigDecimal rawValue;

  @Column(length = 4000)
  private String textValue;

  /** 与指标允许的12位精度一致，同时保留原有32位整数容量。 */
  @Column(precision = 44, scale = 12)
  private BigDecimal value;

  @Column(length = 256)
  private String status;

  @Column(length = 256)
  private String source;

  @Column(length = 256)
  private String sourceId;

  @Column
  private Long observedTime;

  @Column(length = 4000)
  private String reason;

  @Column(length = 256)
  private String reviewerId;

  @Column(length = 256)
  private String replacesId;

  @Column
  private Long meterEpoch;
}
