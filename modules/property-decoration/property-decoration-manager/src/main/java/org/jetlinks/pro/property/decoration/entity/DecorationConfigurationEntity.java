package org.jetlinks.pro.property.decoration.entity;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.Column;
import javax.persistence.Table;
import javax.persistence.Index;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import java.sql.JDBCType;
import org.jetlinks.pro.property.decoration.model.DecorationModels;

/** 装修自有数据；仅通过受控业务服务维护，不暴露通用写入和物理删除。 */
@Getter @Setter @EnableEntityEvent
@Table(name="pd_configuration",indexes={ @Index(name="uk_pd_config_key",columnList="configurationKey",unique=true),
    @Index(name="idx_pd_config_scope",columnList="applicationId,projectId,kind") })
public class DecorationConfigurationEntity extends GenericEntity<String> {

    @Column(length=64, nullable=false, updatable=false) private String applicationId;
    @Column(length=64, nullable=false, updatable=false) private String tenantId;
    @Column(length=64, nullable=false, updatable=false) private String projectId;
    @Column(length=64, nullable=false, updatable=false) private String kind;
    @Column(length=64, nullable=false, updatable=false) private String houseType;
    @Column(length=64, nullable=false, updatable=false) private String decorationType;
    @Column(length=64, nullable=false, updatable=false) private String scene;
    @Column(length=64, nullable=false, updatable=false) private String configurationKey;
    @Column(length=128) private String name;
    @Column private Boolean enabled;
    @Column private Boolean deleted;
    @Column private Long version;
    @Column private Long createTime;
    @Column private Long modifyTime;
    @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class)
    private DecorationModels.Configuration content;

}
