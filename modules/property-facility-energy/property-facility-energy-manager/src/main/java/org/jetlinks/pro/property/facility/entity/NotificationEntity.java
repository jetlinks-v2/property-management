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

/** Notification持久化记录；来源、权限、版本及生命周期由对应领域服务维护。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
  name = "fe_notification",
  indexes = { @Index(
    name = "idx_fe_notification_scope",
    columnList = "tenantId,applicationId,eventId,recipientKey,channel,sequence",
    unique = true
  ), @Index(name = "idx_fe_notification_inbox", columnList = "tenantId,applicationId,userId,channel,readTime,createTime") }
)
public class NotificationEntity extends FacilityEntity {

  @Column(length = 256)
  private String eventId;

  @Column(length = 64)
  private String taskId;
  @Column(length = 64)
  private String faultId;
  @Column(length = 64)
  private String alertId;
  @Column(length = 32)
  private String messageType;
  @Column(length = 256)
  private String taskName;
  @Column(length = 32)
  private String taskKind;
  @Column
  private Integer deviceCount;
  @Column
  private Integer submittedDeviceCount;
  @Column
  private Long scheduledTime;
  @Column
  private Long deadline;

  @Column(length = 256)
  private String deviceId;

  /** Project-level energy plan; mutually exclusive with deviceId. */
  @Column(length = 256)
  private String targetId;

  @Column(length = 256)
  private String bindingId;

  @Column(length = 256)
  private String userId;

  @Column(length = 256)
  private String telephone;

  @Column(length = 256)
  private String recipientKey;

  @Column(length = 256)
  private String channel;

  @Column
  private Integer sequence;

  @Column(length = 256)
  private String title;

  @Column(length = 4000)
  private String message;

  @Column(length = 256)
  private String state;

  @Column
  private Integer attempts;

  @Column
  private Long nextAttemptTime;

  @Column
  private Long readTime;

  @Column(length = 256)
  private String notifierId;

  @Column(length = 256)
  private String templateId;

  @Column(length = 256)
  private String providerMessageId;

  @Column(length = 4000)
  private String failureReason;

  @Column
  private Long deliveredTime;
}
