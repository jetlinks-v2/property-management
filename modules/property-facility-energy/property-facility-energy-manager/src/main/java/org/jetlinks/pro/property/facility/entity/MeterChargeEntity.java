package org.jetlinks.pro.property.facility.entity;

import java.math.BigDecimal;
import java.sql.JDBCType;
import java.util.*;
import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.*;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.jetlinks.pro.property.facility.model.MeterChargeVersion;

/** Current project/energy billing configuration; bills freeze a separate source snapshot. */
@Getter @Setter @EnableEntityEvent
@Table(name = "fe_meter_charge", indexes = @Index(name = "uk_fe_meter_charge", columnList = "tenantId,applicationId,projectId,energyType", unique = true))
public class MeterChargeEntity extends FacilityEntity {
  @com.fasterxml.jackson.annotation.JsonIgnore
  @com.alibaba.fastjson.annotation.JSONField(serialize = false)
  @Column @JsonCodec @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
  private List<MeterChargeVersion> history = new ArrayList<>();
  @Column(length = 24) private String energyType;
  @Column(length = 64) private String subjectId;
  @Column(length = 64) private String standardId;
  @Column(length = 128) private String usageVariable;
  @Column @JsonCodec @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
  private Map<String, BigDecimal> fixedValues = new TreeMap<>();
  @Column(length = 10) private String effectiveFrom;
  @Column(length = 10) private String effectiveTo;
  @Column private Boolean enabled = true;
}
