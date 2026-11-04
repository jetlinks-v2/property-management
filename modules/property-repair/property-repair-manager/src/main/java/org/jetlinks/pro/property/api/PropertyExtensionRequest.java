package org.jetlinks.pro.property.api;
import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.Size;
/** 可编辑的共享业务扩展；Cloud 源ID、租户、名称和层级不接受客户端覆盖。 */
@Getter @Setter
public class PropertyExtensionRequest {
 private Boolean businessProject;
 private Boolean employee;
 private Boolean owner;
 @Size(max=128) private String displayName;
 @Size(max=1024) private String address;
 @Size(max=64) private String businessProjectId;
}
