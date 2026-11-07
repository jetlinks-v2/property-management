package org.jetlinks.pro.property.facility.model;

import jakarta.validation.constraints.*;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 绑定采用设备版本CAS串行化，客户身份由共享档案返回，不能由请求冒充。 */
@Getter
@Setter
public class BindingInput {

  @NotNull
  private Long version;

  @NotBlank
  private String customerId;

  @NotBlank
  private String spaceId;

  @Size(max = 20)
  private List<Contact> contacts = List.of();

  private String reason;
}
