package org.jetlinks.pro.property.facility.model;

import jakarta.validation.constraints.*;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 员工动作只接收版本和业务内容，操作人来自真实会话。submissionId用于离线重放幂等。 */
@Getter
@Setter
public class TaskCommand {

  @NotNull
  private Long version;

  private String userId;
  private String comment;

  @Size(max = 100)
  private List<CheckResult> results;

  private String submissionId;
}
