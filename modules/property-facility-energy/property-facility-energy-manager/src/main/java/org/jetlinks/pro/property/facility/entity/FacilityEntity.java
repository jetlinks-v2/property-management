package org.jetlinks.pro.property.facility.entity;

import javax.persistence.Column;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.ExtendableEntity;
import org.hswebframework.web.api.crud.entity.RecordCreationEntity;

/** 所有业务记录的可信租户、应用、项目及审计归属；只能由领域入口赋值。 */
@Getter
@Setter
public abstract class FacilityEntity
  extends ExtendableEntity<String>
  implements RecordCreationEntity
{

  @Column(length = 64, nullable = false, updatable = false)
  private String tenantId;

  @Column(length = 64, nullable = false, updatable = false)
  private String applicationId;

  @Column(length = 64)
  private String projectId;

  @Column(length = 64)
  private String organizationId;

  @Column(length = 128)
  private String projectName;

  @Column(length = 64, updatable = false)
  private String creatorId;

  @Column(length = 128, updatable = false)
  private String creatorName;

  @Column(updatable = false)
  private Long createTime;

  @Column
  private Long modifyTime;

  @Column
  private Boolean deleted = false;

  @Column
  private Long version = 0L;

  public String getTenantId() {
    return tenantId != null
      ? tenantId
      : java.util.Objects.toString(getExtension("tenantId"), null);
  }

  public void setTenantId(String value) {
    tenantId = value;
    setExtension("tenantId", value);
  }
}
