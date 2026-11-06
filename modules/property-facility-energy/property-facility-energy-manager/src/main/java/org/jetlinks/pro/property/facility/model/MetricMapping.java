package org.jetlinks.pro.property.facility.model;

import java.math.BigDecimal;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** MetricMapping领域快照，版本随所属档案保存；不包含设备认证或账号凭据。 */
@Getter
@Setter
public class MetricMapping {

  private String propertyId;
  private String name;
  private String kind;
  private String unit;
  private BigDecimal multiplier;
  private Integer scale;
}
