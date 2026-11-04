package org.jetlinks.pro.smartpark.handover.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.sql.JDBCType;
import java.util.List;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** Persisted handover process; tenant/application/project fields are server controlled. */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "sp_handover_process",
        indexes = {
            @Index(
                    name = "idx_ho_process_scope",
                    columnList = "tenantId,applicationId,projectId,deleted")
        })
public class HandoverProcessEntity extends HandoverEntity {
    @Column(length = 256)
    private String name;

    @Column
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    private List<StageState> stages;

    @Getter
    @Setter
    public static class StageState {
        private String code;
        private String status;
        private Integer version;
        private Long startTime;
        private Long endTime;
    }
}
