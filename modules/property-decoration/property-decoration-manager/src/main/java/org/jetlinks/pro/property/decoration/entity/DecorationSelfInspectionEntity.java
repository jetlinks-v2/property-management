package org.jetlinks.pro.property.decoration.entity;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import java.sql.JDBCType;
import java.util.List;

/** 业主申请提交时的装前自检快照；逻辑删除不影响申请历史和附件归属。 */
@Getter @Setter @EnableEntityEvent
@Table(name="pd_self_inspection", indexes={
    @Index(name="uk_pd_self_application", columnList="decorationId", unique=true),
    @Index(name="idx_pd_self_scope", columnList="applicationId,tenantId,projectId,createTime")
})
public class DecorationSelfInspectionEntity extends GenericEntity<String> {
    @Column(length=64, nullable=false, updatable=false) private String applicationId;
    @Column(length=64, nullable=false, updatable=false) private String tenantId;
    @Column(length=64, nullable=false, updatable=false) private String projectId;
    @Column(length=256, updatable=false) private String projectName;
    @Column(length=64, nullable=false, updatable=false) private String decorationId;
    @Column(length=64, nullable=false, updatable=false) private String spaceId;
    @Column(length=256, updatable=false) private String spaceName;
    @Column(length=64, nullable=false, updatable=false) private String number;
    @Column(length=64, updatable=false) private String creatorId;
    @Column(length=128, updatable=false) private String creatorName;
    @Column(updatable=false) private Long createTime;
    @Column(updatable=false) private Integer status;
    @Column(length=2000, updatable=false) private String remark;
    @Column(updatable=false) @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR, javaType=String.class)
    private List<String> fileIds;
    @Column private Boolean deleted;
    @Column private Long version;
    @Column private Long modifyTime;
}
