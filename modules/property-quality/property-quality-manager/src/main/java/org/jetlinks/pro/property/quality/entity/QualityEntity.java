package org.jetlinks.pro.property.quality.entity;
import lombok.Getter;
import lombok.Setter;
import javax.persistence.Column;
import org.hswebframework.web.api.crud.entity.ExtendableEntity;
import org.hswebframework.web.api.crud.entity.RecordCreationEntity;
/** 品质记录的可信租户扩展、应用、项目和审计字段；请求不得直接覆盖这些归属。 */
@Getter @Setter
public abstract class QualityEntity extends ExtendableEntity<String> implements RecordCreationEntity {
 /** 后台计划、导出与通知也需持久化租户；不能依赖仅资产表才注入的动态扩展。 */
 @Column(length=64,nullable=false,updatable=false) private String tenantId;
 /** 平台资产表会将同名租户列映射到扩展，非资产子表使用显式持久化字段。 */
 public String getTenantId(){return tenantId!=null?tenantId:java.util.Objects.toString(getExtension("tenantId"),null);}
 public void setTenantId(String value){tenantId=value;setExtension("tenantId",value);}
 @Column(length=64,nullable=false,updatable=false) private String applicationId;
 @Column(length=64,updatable=false) private String projectId;
 @Column(length=64,updatable=false) private String organizationId;
 @Column(length=128) private String projectName;
 @Column(length=64,updatable=false) private String creatorId;
 @Column(length=128,updatable=false) private String creatorName;
 @Column(updatable=false) private Long createTime;
 @Column private Long modifyTime;
 @Column private Boolean deleted=false;
 @Column private Long version=0L;
}
