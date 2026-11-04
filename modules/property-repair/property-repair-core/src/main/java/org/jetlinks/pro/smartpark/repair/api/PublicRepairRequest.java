package org.jetlinks.pro.smartpark.repair.api;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

/** 公开表单没有租户、应用、项目、渠道、登记人员或设备字段，均不能由匿名请求指定。 */
@Getter @Setter
public class PublicRepairRequest {
    @NotBlank @Size(max=64) private String categoryId;
    @NotBlank @Size(max=64) private String spaceId;
    @NotNull @Size(max=800) private String description;
    @NotBlank @Size(max=64) private String contactName;
    @NotBlank @Pattern(regexp="^1[3-9]\\d{9}$|^(0\\d{2,3}-?)?\\d{7,8}$") private String contactPhone;
    @Size(max=512) private String location;
    @NotNull @Size(max=50) private List<@NotBlank String> labelIds=List.of();
    @Min(1) @Max(3) private Integer urgency;
    @NotNull @Size(max=100) private List<@NotBlank String> descriptionChoiceIds=List.of();
    @NotNull @Size(max=20) private List<@NotBlank String> uploadIds=List.of();
}
