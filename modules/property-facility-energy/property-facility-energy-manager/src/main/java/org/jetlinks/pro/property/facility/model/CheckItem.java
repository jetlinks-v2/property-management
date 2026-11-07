package org.jetlinks.pro.property.facility.model;

import java.math.BigDecimal;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** CheckItem领域快照，版本随所属档案保存；不包含设备认证或账号凭据。 */
@Getter
@Setter
public class CheckItem {

  private String id;
  private String name;
  private String kind;
  private Boolean photoRequired;
  private Boolean required;
  private BigDecimal minimum;
  private BigDecimal maximum;
  private List<String> options;
}
