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
@Table(name="pd_attachment",indexes={ @Index(name="idx_pd_attachment_scope",columnList="applicationId,projectId,creatorId") })
public class DecorationAttachmentEntity extends GenericEntity<String> {

    @Column(length=64, nullable=false, updatable=false) private String applicationId;
    @Column(length=64, nullable=false, updatable=false) private String tenantId;
    @Column(length=64, nullable=false, updatable=false) private String projectId;
    @Column(length=16, nullable=false, updatable=false) private String purpose;
    @Column(length=64) private String decorationId;
    @Column(length=64, nullable=false, updatable=false) private String creatorId;
    @Column(length=128, updatable=false) private String fileId;
    @Column(length=512, updatable=false) private String name;
    @Column(length=128, updatable=false) private String contentType;
    @Column private Long size;
    @Column private Long createTime;

}
