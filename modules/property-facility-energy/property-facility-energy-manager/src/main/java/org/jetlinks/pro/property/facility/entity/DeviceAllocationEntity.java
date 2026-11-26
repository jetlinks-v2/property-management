package org.jetlinks.pro.property.facility.entity;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.Transient;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;

/** 来源目录与业务台账分离；仅保存明确分配的项目，同步后才创建或迁移设备资产。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "fe_device_allocation", indexes = {
  @Index(name = "idx_fe_allocation_source", columnList = "tenantId,applicationId,cloudDeviceId", unique = true),
  @Index(name = "idx_fe_allocation_page", columnList = "tenantId,applicationId,deleted,createTime,id")
})
public class DeviceAllocationEntity extends FacilityEntity {
  @Column(length = 256)
  private String cloudDeviceId;
  @Column(length = 256)
  private String name;
  @Column(length = 256)
  private String productName;
  @Column
  private Boolean projectConfirmed = false;
  @Column
  private Long allocationSince;
  @Transient
  private String assetProjectId;
}
