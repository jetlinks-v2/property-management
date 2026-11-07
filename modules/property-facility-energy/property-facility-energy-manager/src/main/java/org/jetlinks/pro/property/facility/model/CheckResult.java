package org.jetlinks.pro.property.facility.model;

import java.math.BigDecimal;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** CheckResult领域快照，版本随所属档案保存；不包含设备认证或账号凭据。 */
@Getter
@Setter
public class CheckResult {

  private String itemId;
  private String value;
  private Boolean abnormal;
  private String remark;
  private List<String> attachments;
}
