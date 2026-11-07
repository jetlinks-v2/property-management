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

/** Binding持久化记录；来源、权限、版本及生命周期由对应领域服务维护。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
  name = "fe_binding",
  indexes = @Index(
    name = "idx_fe_binding_scope",
    columnList = "tenantId,applicationId,deviceId,startTime",
    unique = true
  )
)
public class BindingEntity extends FacilityEntity {

  @Column(length = 256)
  private String deviceId;

  @Column(length = 256)
  private String customerId;

  @Column(length = 256)
  private String customerName;

  @Column(length = 256)
  private String userId;

  @Column(length = 256)
  private String spaceId;

  @Column
  private Long startTime;

  @Column
  private Long endTime;

  @Column
  @JsonCodec
  @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
  private List<Contact> contacts = new ArrayList<>();

  @Column(length = 256)
  private String endReason;
}
