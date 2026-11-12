package org.jetlinks.pro.property.facility.model;

import jakarta.validation.constraints.*;
import java.util.List;

/** 故障状态动作只接收版本、操作说明和必要业务参数，不接受客户端覆盖归属或状态。 */
public record FaultCommand(
  @NotNull @Min(0) Long version,
  @Size(max = 4000) String comment,
  @Size(max = 64) String userId,
  @Size(max = 20) List<String> attachments
) {}
