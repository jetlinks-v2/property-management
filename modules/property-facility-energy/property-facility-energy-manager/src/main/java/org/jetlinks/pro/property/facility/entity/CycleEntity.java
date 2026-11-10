package org.jetlinks.pro.property.facility.entity;

import java.sql.JDBCType;
import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.*;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.jetlinks.pro.assets.annotation.Asset;
import org.jetlinks.pro.property.facility.model.CycleRule;

/** 可复用的周期计划；任务配置引用它，检查标准不承载排程。 */
@Getter
@Setter
@EnableEntityEvent
@Asset(type = "facilityCycle")
@Table(name = "fe_cycle", indexes = @Index(name = "idx_fe_cycle_scope", columnList = "tenantId,applicationId,projectId"))
public class CycleEntity extends FacilityEntity {
  @Column(length = 256)
  private String name;
  @Column(length = 256)
  private String kind;
  @Column
  @JsonCodec
  @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
  private CycleRule rule;
  @Column
  private Boolean enabled = true;
}
