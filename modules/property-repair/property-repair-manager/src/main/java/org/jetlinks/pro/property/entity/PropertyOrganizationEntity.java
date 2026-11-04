package org.jetlinks.pro.property.entity;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.Column;
import javax.persistence.Table;
import javax.persistence.Index;
import java.sql.JDBCType;
import java.util.List;
import org.hswebframework.web.api.crud.entity.ExtendableEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
/** 共享的组织投影与业务项目标签；ID 与 Cloud 组织一致，不按应用复制。 */
@Getter @Setter @EnableEntityEvent
@Table(name="pp_organization",indexes=@Index(name="idx_pp_organization_tenant",columnList="tenantId"))
public class PropertyOrganizationEntity extends ExtendableEntity<String> {
 @Column(length=64,nullable=false) private String tenantId;
 @Column(length=128) private String sourceName;
 @Column(length=64) private String parentId;
 @Column(length=512) private String sourcePath;
 @Column(length=64) private String code;
 @Column private Boolean businessProject;
 @Column(length=128) private String displayName;
 @Column(length=1024) private String address;
 @Column private Boolean sourceEnabled = true;
 @Column private Long syncTime;
 @Column private Long modifyTime;
}
