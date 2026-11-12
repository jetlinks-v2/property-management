package org.jetlinks.pro.property.facility.entity;

import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** 每个应用项目一份员工通知策略；接收人始终由业务参与者和项目组织决定。 */
@Getter
@Setter
@Table(name = "fe_notification_policy", indexes = @Index(name = "idx_fe_notice_policy", columnList = "tenantId,applicationId,projectId", unique = true))
public class NotificationPolicyEntity extends FacilityEntity {
  @Column private Boolean taskEnabled = true;
  @Column private Boolean faultEnabled = true;
  @Column private Boolean alertEnabled = true;
}
