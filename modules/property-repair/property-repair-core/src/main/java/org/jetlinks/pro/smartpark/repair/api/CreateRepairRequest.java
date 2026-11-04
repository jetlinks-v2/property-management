package org.jetlinks.pro.smartpark.repair.api;

import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.*;
import java.util.List;
/** 发起工单；租户、应用、发起人和工单组织由服务端可信上下文确定。 */
@Getter @Setter
public class CreateRepairRequest {
 @NotBlank @Size(max=64) private String businessProjectId;
 @NotBlank @Size(max=64) private String categoryId;
 @NotBlank @Size(max=64) private String channelId;
 // 旧表单没有独立标题；列表摘要由服务端从报事描述生成。
 @NotNull @Size(max=800) private String description;
 @NotNull @Min(1) @Max(2) private Integer reportSource;
 @NotNull @Min(1) @Max(2) private Integer positionType;
 @Min(1) @Max(3) private Integer urgency;
 @NotNull @Size(max=50) private List<@NotBlank String> labelIds=List.of();
 @Size(max=100) private List<@NotBlank String> descriptionChoiceIds;
 @Size(max=512) private String location;
 @NotBlank @Size(max=64) private String contactName;
 @NotBlank @Size(max=64) @Pattern(regexp="^1[3-9]\\d{9}$|^(0\\d{2,3}-?)?\\d{7,8}$") private String contactPhone;
 @Size(max=64) private String spaceId;
 @Size(max=64) private String deviceId;
 @Size(max=20) private List<@NotBlank String> attachmentIds;
}
