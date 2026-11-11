package org.jetlinks.pro.property.facility.entity;

import java.math.BigDecimal;
import java.sql.JDBCType;
import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.*;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.jetlinks.pro.property.finance.meter.MeterBillingModels;

/** One reviewed monthly snapshot per facility meter; authorization is correlated to deviceId. */
@Getter @Setter @EnableEntityEvent
@Table(name="fe_meter_bill",indexes={
  @Index(name="uk_fe_meter_bill_month",columnList="tenantId,applicationId,deviceId,month",unique=true),
  @Index(name="idx_fe_meter_bill_review",columnList="tenantId,applicationId,projectId,state,month")
})
public class MeterBillEntity extends FacilityEntity {
  @Column(length=64) private String deviceId;
  @Column(length=256) private String deviceName;
  @Column(length=24) private String meterType;
  @Column(length=7) private String month;
  @Column(length=1024) private String fullName;
  @Column(length=256) private String customerName;
  @Column(precision=24,scale=6) private BigDecimal quantity;
  @Column(precision=24,scale=2) private BigDecimal amount;
  @Column(length=24) private String state;
  @Column(length=10) private String receivableDate;
  @Column(length=10) private String dueDate;
  @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class)
  private MeterBillingModels.Result result;
  @Column(length=64) private String reviewerId;
  @Column(length=128) private String reviewerName;
  @Column private Long reviewedTime;
  @Column(length=512) private String comment;
  @Column(length=2000) private String error;
}
