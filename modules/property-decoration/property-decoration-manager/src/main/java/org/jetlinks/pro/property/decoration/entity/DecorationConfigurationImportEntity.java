package org.jetlinks.pro.property.decoration.entity;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.jetlinks.pro.property.decoration.model.DecorationConfigurationImports;
import javax.persistence.Column;
import javax.persistence.Table;
import java.sql.JDBCType;

/** 配置整批导入的成功收据，与全部配置在一个事务提交；不开放通用CRUD。 */
@Getter @Setter @EnableEntityEvent @Table(name="pd_configuration_import")
public class DecorationConfigurationImportEntity extends GenericEntity<String> {
    @Column(length=64, nullable=false, updatable=false) private String applicationId;
    @Column(length=64, nullable=false, updatable=false) private String tenantId;
    @Column(length=64, nullable=false, updatable=false) private String actorId;
    @Column(length=64, nullable=false, updatable=false) private String fileDigest;
    @Column(nullable=false, updatable=false) private Long createTime;
    @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR, javaType=String.class)
    private DecorationConfigurationImports.Result result;
}
