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

/** Device持久化记录；来源、权限、版本及生命周期由对应领域服务维护。 */
@Getter
@Setter
@EnableEntityEvent
@Asset(type = "facilityDevice")
@Table(
  name = "fe_device",
  indexes = @Index(
    name = "idx_fe_device_scope",
    columnList = "tenantId,applicationId,cloudDeviceId",
    unique = true
  )
)
public class DeviceEntity extends FacilityEntity {

  @Column(length = 256)
  private String cloudDeviceId;

  @Column(length = 256)
  private String sourceName;

  @Column(length = 256)
  private String sourceCode;

  @Column(length = 256)
  private String productId;

  @Column(length = 256)
  private String productName;

  @Column(length = 256)
  private String sourceState;

  @Column(length = 256)
  private String syncState;

  @Column
  private Boolean projectConfirmed = false;

  @Column
  private Long lastSeenTime;

  @Column(length = 256)
  private String pendingProjectId;

  @Column
  @JsonCodec
  @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
  private List<String> candidateProjectIds = new ArrayList<>();

  @Column(length = 256)
  private String name;

  @Column(length = 256)
  private String systemName;

  @Column(length = 256)
  private String categoryName;

  @Column(length = 256)
  private String specification;

  @Column
  private BigDecimal power;

  @Column(length = 256)
  private String manufacturer;

  @Column
  private BigDecimal purchasePrice;

  @Column(length = 256)
  private String purchaseDate;

  @Column(length = 256)
  private String startDate;

  @Column(length = 256)
  private String warrantyDate;

  @Column(length = 256)
  private String maintainer;

  @Column(length = 256)
  private String maintainerUserId;

  @Column(length = 256)
  private String responsibleUserId;

  @Column(length = 256)
  private String spaceId;

  @Column(length = 256)
  private String location;

  @Column(length = 256)
  private String floor;

  @Column(length = 4000)
  private String remark;

  @Column
  @JsonCodec
  @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
  private List<String> attachments = new ArrayList<>();

  @Column
  private Boolean inspectionEnabled = false;

  @Column
  private Boolean maintenanceEnabled = false;

  @Column
  private Boolean energyEnabled = false;
}
