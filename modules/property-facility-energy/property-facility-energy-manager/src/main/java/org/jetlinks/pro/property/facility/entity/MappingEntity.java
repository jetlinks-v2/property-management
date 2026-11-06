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

/** Mapping持久化记录；来源、权限、版本及生命周期由对应领域服务维护。 */
@Getter
@Setter
@EnableEntityEvent
@Asset(type = "facilityMapping")
@Table(
  name = "fe_mapping",
  indexes = @Index(
    name = "idx_fe_mapping_scope",
    columnList = "tenantId,applicationId,deviceId,projectId",
    unique = true
  )
)
public class MappingEntity extends FacilityEntity {

  @Column(length = 256)
  private String name;

  @Column(length = 256)
  private String deviceId;

  @Column(length = 256)
  private String productId;

  @Column(length = 256)
  private String energyType;

  @Column(length = 256)
  private String usageType;

  /** Only this cumulative/increment metric contributes to aggregate consumption and plans. */
  @Column(length = 256)
  private String usagePropertyId;

  @Column(length = 256)
  private String parentDeviceId;

  @Column(length = 256)
  private String meterLevel;

  @Column
  @JsonCodec
  @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
  private List<MetricMapping> metrics = new ArrayList<>();

  @Column
  private Long lastCollectedTime;

  /** Completion time of the latest collection attempt, distinct from its historical cursor. */
  @Column
  private Long lastCollectionTime;

  @Column
  private Long lastCollectionSuccessTime;

  /** Bounded exception type only; never persist remote payloads or credentials. */
  @Column(length = 256)
  private String collectionError;

  @Column
  private Long staleAfterSeconds;

  @Column
  private Boolean enabled = false;
}
