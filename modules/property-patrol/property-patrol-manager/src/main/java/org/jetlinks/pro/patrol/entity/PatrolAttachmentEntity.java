package org.jetlinks.pro.patrol.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.EnumCodec;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.jetlinks.pro.assets.annotation.Asset;

import javax.persistence.Column;
import javax.persistence.Table;

/** 平台存文件，巡更保存用途及所属资产；只登记由服务端实际上传成功的图片。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "pt_attachment")
@Asset(type = "patrolAttachment")
public class PatrolAttachmentEntity extends PatrolConfigEntity {
    public enum Purpose { example, evidence }

    @Column(length = 16, nullable = false, updatable = false)
    @EnumCodec
    @ColumnType(javaType = String.class)
    private Purpose purpose;

    @Column(length = 2048, nullable = false, updatable = false)
    @JsonIgnore
    private String platformFileId;

    @Column(length = 4096, nullable = false, updatable = false)
    @JsonIgnore
    private String internalUrl;

    @Column(length = 64, nullable = false, updatable = false)
    private String mediaType;

    @Column(nullable = false, updatable = false)
    private long length;
}
