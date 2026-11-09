package org.jetlinks.pro.smartpark.handover.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.jetlinks.pro.smartpark.handover.api.FileRef;

import java.sql.JDBCType;
import java.util.List;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** Persisted handover ledger; tenant/application/project fields are server controlled. */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "sp_handover_ledger",
        indexes = {
            @Index(
                    name = "idx_ho_ledger_scope",
                    columnList = "tenantId,applicationId,projectId,deleted")
        })
public class HandoverLedgerEntity extends HandoverEntity {
    @Column(length = 256, nullable = false)
    private String title;

    @Column(length = 64)
    private String categoryId;
    @javax.persistence.Transient private String categoryName;
    @javax.persistence.Transient private String projectName;
    @Column(length = 64) private String organizationId;
    @Column(length = 128) private String organizationName;
    @Column(length = 64) private String entryUserId;
    @Column(length = 128) private String entryUserName;
    @Column private Long entryTime;
    /** 现场检查时间；导入允许填写，不替代服务端录入审计时间。 */
    @Column private Long inspectionTime;
    @Column(length = 64) private String supplementUserId;
    @Column(length = 128) private String supplementUserName;
    @Column private Long supplementTime;

    @Column(length = 512)
    private String location;

    @Column(length = 4000)
    private String description;

    @Column(length = 4000)
    private String proposedSolution;

    @Column(length = 4000)
    private String rectificationOpinion;

    @Column(length = 2000)
    private String remark;

    @Column
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    private List<FileRef> files = List.of();
}
