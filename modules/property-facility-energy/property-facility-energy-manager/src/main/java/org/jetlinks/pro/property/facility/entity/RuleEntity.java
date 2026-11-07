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

/** Rule持久化记录；来源、权限、版本及生命周期由对应领域服务维护。 */
@Getter
@Setter
@EnableEntityEvent
@Asset(type = "facilityRule")
@Table(
  name = "fe_rule",
  indexes = @Index(
    name = "idx_fe_rule_scope",
    columnList = "tenantId,applicationId,projectId",
    unique = false
  )
)
public class RuleEntity extends FacilityEntity {

  @Column(length = 256)
  private String name;

  @Column(length = 256)
  private String ruleType;

  @Column(length = 256)
  private String energyType;

  @Column(length = 256)
  private String deviceId;

  @Column(length = 256)
  private String groupId;

  @Column(length = 256)
  private String propertyId;

  @Column(length = 256)
  private String stateValue;

  @Column(length = 256)
  private String notifyUserId;

  @Column(length = 256)
  private String period;

  @Column(precision = 44, scale = 12)
  private BigDecimal threshold;

  @Column(precision = 44, scale = 12)
  private BigDecimal criticalThreshold;

  /** 保留小数比例；默认零位小数会把0.8写成1，导致规则无法再次保存。 */
  @Column(precision = 44, scale = 12)
  private BigDecimal nearRatio;

  @Column
  private Long staleAfterSeconds;

  @Column
  private Integer repeatHours;

  @Column
  private Boolean smsEnabled = false;

  @Column(length = 256)
  private String smsNotifierId;

  @Column(length = 256)
  private String smsTemplateId;

  @Column
  private Boolean enabled = false;
}
