package org.jetlinks.pro.smartpark.handover.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.jetlinks.pro.smartpark.handover.api.FileRef;

import java.sql.JDBCType;
import java.util.List;
import java.util.Map;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** Persisted handover record; tenant/application/project fields are server controlled. */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "sp_handover_record",
        indexes = {
            @Index(
                    name = "idx_ho_record_scope",
                    columnList = "tenantId,applicationId,projectId,deleted")
        })
public class HandoverRecordEntity extends HandoverEntity {
    @Column(length = 32, nullable = false)
    private String stage;

    @Column(length = 256, nullable = false)
    private String title;

    @Column(length = 64)
    private String categoryId;

    @Column(length = 4000)
    private String description;

    @Column
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    private List<String> personIds = List.of();

    @Column(length = 2000)
    private String participantNames;

    @Column private Long time;

    @Column
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    private List<FileRef> files = List.of();

    @Column(length = 2000)
    private String remark;

    @Column
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    private Map<String, String> properties = Map.of();
}
