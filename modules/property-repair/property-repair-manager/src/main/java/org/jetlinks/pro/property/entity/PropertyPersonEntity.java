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
/** 共享人员投影；员工与业主为独立标记，同一人可以同时具备两种身份。 */
@Getter @Setter @EnableEntityEvent
@Table(name="pp_person",indexes=@Index(name="idx_pp_person_tenant",columnList="tenantId"))
public class PropertyPersonEntity extends ExtendableEntity<String> {
 @Column(length=64,nullable=false) private String tenantId;
 @Column(length=128) private String sourceName;
 @Column(length=128) private String username;
 @Column private Byte sourceStatus;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class)
 private List<String> organizationIds;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class)
 private List<String> roleIds;
 @Column private Boolean employee;
 @Column private Boolean owner;
 @Column(length=128) private String displayName;
 @Column private Long syncTime;
 @Column private Long modifyTime;
}
