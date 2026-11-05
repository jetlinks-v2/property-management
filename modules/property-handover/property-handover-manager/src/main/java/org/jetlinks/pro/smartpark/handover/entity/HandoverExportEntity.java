package org.jetlinks.pro.smartpark.handover.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.jetlinks.pro.smartpark.handover.api.ExportRequest;

import java.sql.JDBCType;
import java.util.List;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** Persisted handover export; tenant/application/project fields are server controlled. */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "sp_handover_export",
        indexes = {
            @Index(
                    name = "idx_ho_export_scope",
                    columnList = "tenantId,applicationId,projectId,deleted")
        })
public class HandoverExportEntity extends HandoverEntity {
    @Column(length = 32, nullable = false)
    private String kind;

    @Column(length = 32, nullable = false)
    private String status;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(length = 64)
    private String fileId;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(length = 64)
    private String manifestFileId;

    @Column(length = 256)
    private String fileName;

    @Column(length = 2000)
    private String error;

    @Column private Long finishTime;

    @Column(length = 64)
    private String leaseOwner;

    @Column private Long leaseUntil;
    @Column private Integer attempts;

    @Column
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private ExportRequest request;

    @Column
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private List<String> sourceIds;
}
