package org.jetlinks.pro.property.facility.model;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** 人工补录仅保存申请，不改Cloud源读数；审核后才参与统计。 */
@Getter
@Setter
public class ReadingInput {

  @NotBlank
  private String propertyId;

  @NotNull
  private BigDecimal value;

  @NotNull
  private Long observedTime;

  @NotBlank
  private String reason;

  private String replacesId;
}
