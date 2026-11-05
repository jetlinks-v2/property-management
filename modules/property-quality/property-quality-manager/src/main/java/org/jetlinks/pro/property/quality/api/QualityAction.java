package org.jetlinks.pro.property.quality.api;
import lombok.Getter;import lombok.Setter;import jakarta.validation.Valid;import jakarta.validation.constraints.*;import java.util.*;
/** 核查、整改和指派表单；人员姓名、所属应用及项目由服务端解析。 */
@Getter @Setter public class QualityAction {
 @NotNull private Long version;
 @Size(max=1000) @Valid private List<Result> results=List.of();
 @Size(max=1000) private List<@NotBlank String> recordIds=List.of();
 @Size(max=50) private List<@NotBlank String> assigneeIds=List.of();
 @Size(max=8000) private String description;
 @Getter @Setter public static class Result {
  @NotBlank private String recordId;
  @NotNull @Min(0) @Max(1) private Integer isQualified;
  @NotNull private Integer score;
  @Size(max=8000) private String checkResult;
  @Size(max=50) private List<@NotBlank String> photoIds=List.of();
 }
}
