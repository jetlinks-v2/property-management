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

/** Group持久化记录；来源、权限、版本及生命周期由对应领域服务维护。 */
@Getter
@Setter
@EnableEntityEvent
@Asset(type = "facilityGroup")
@Table(
  name = "fe_group",
  indexes = @Index(
    name = "idx_fe_group_scope",
    columnList = "tenantId,applicationId,projectId",
    unique = false
  )
)
public class GroupEntity extends FacilityEntity {

  @Column(length = 256)
  private String name;

  @Column(length = 256)
  private String location;

  @Column
  @JsonCodec
  @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
  private List<String> deviceIds = new ArrayList<>();

  @Column(length = 256)
  private String systemFilter;

  @Column(length = 256)
  private String categoryFilter;
}
