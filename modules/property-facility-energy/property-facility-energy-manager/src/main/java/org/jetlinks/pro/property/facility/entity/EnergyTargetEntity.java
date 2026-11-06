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

/** EnergyTarget持久化记录；来源、权限、版本及生命周期由对应领域服务维护。 */
@Getter
@Setter
@EnableEntityEvent
@Asset(type = "facilityTarget")
@Table(
  name = "fe_energy_target",
  indexes = @Index(
    name = "idx_fe_energy_target_scope",
    columnList = "tenantId,applicationId,projectId,energyType,year,month",
    unique = true
  )
)
public class EnergyTargetEntity extends FacilityEntity {

  @Column(length = 256)
  private String name;

  @Column(length = 256)
  private String energyType;

  @Column
  private Integer year;

  @Column
  private Integer month;

  @Column(precision = 44, scale = 12)
  private BigDecimal targetUsage;

  @Column(precision = 44, scale = 12)
  private BigDecimal managementArea;
}
