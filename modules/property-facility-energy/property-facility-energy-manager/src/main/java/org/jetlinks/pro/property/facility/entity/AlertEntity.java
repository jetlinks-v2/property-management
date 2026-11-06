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

/** Alert持久化记录；来源、权限、版本及生命周期由对应领域服务维护。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
  name = "fe_alert",
  indexes = @Index(
    name = "idx_fe_alert_scope",
    columnList = "tenantId,applicationId,ruleId,deviceId",
    unique = true
  )
)
public class AlertEntity extends FacilityEntity {

  @Column(length = 256)
  private String name;

  @Column(length = 256)
  private String deviceId;

  /** Project-level energy plan; mutually exclusive with deviceId. */
  @Column(length = 256)
  private String targetId;

  @Column(length = 256)
  private String ruleId;

  @Column(length = 256)
  private String ruleType;

  @Column(length = 256)
  private String energyType;

  @Column(length = 256)
  private String periodKey;

  @Column(length = 256)
  private String state;

  @Column(length = 256)
  private String level;

  @Column(precision = 44, scale = 12)
  private BigDecimal value;

  @Column(precision = 44, scale = 12)
  private BigDecimal threshold;

  @Column(length = 256)
  private String unit;

  @Column
  private Long firstTime;

  @Column
  private Long lastTime;

  @Column
  private Long recoveredTime;

  @Column
  private Long lastNotifiedTime;

  @Column
  private Integer notificationSequence;

  @Column(length = 256)
  private String handlerId;

  @Column(length = 4000)
  private String comment;
}
