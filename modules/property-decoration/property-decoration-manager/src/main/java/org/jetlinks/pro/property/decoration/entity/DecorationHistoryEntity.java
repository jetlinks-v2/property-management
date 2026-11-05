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
@Table(name="pd_history",indexes={ @Index(name="uk_pd_history_request",columnList="requestKey",unique=true),
    @Index(name="idx_pd_history_version",columnList="decorationId,version") })
public class DecorationHistoryEntity extends GenericEntity<String> {

    @Column(length=64, nullable=false, updatable=false) private String decorationId;
    @Column(length=64, nullable=false, updatable=false) private String requestKey;
    @Column(length=64, updatable=false) private String requestDigest;
    @Column(length=64, updatable=false) private String action;
    @Column(length=64, updatable=false) private String actorId;
    @Column(length=128, updatable=false) private String actorName;
    @Column(length=64, updatable=false) private String beforeStatus;
    @Column(length=64, updatable=false) private String afterStatus;
    @Column(length=2000, updatable=false) private String remark;
    @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class)
    private DecorationApplicationEntity snapshot;
    @Column private Long version;
    @Column private Long createTime;

}
