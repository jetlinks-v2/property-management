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
/** 共享的 Cloud 空间投影；业务项目归属为共享扩展，源同步不覆盖。 */
@Getter @Setter @EnableEntityEvent
@Table(name="pp_space",indexes=@Index(name="idx_pp_space_tenant",columnList="tenantId"))
public class PropertySpaceEntity extends ExtendableEntity<String> {
 @Column(length=64,nullable=false) private String tenantId;
 @Column(length=128) private String sourceName;
 @Column(length=64) private String parentId;
 @Column(length=512) private String sourcePath;
 @Column(length=64) private String spaceTypeId;
 @Column(length=64) private String code;
 @Column(length=64) private String businessProjectId;
 @Column(length=128) private String displayName;
 @Column private Boolean sourceEnabled = true;
 @Column private Long syncTime;
 @Column private Long modifyTime;
}
