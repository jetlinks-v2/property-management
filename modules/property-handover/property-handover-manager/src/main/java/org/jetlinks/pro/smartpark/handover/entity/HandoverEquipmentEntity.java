package org.jetlinks.pro.smartpark.handover.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.sql.JDBCType;
import java.util.Map;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** Persisted handover equipment; tenant/application/project fields are server controlled. */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "sp_handover_equipment",
        indexes = {
            @Index(
                    name = "idx_ho_equipment_scope",
                    columnList = "tenantId,applicationId,projectId,deleted")
        })
public class HandoverEquipmentEntity extends HandoverEntity {
    @Column(length = 256, nullable = false)
    private String name;

    @Column(length = 128)
    private String code;

    @Column(length = 64)
    private String categoryId;

    // 设备分类是旧业务自由文本，不能与项目问题分类共用 ID。
    @Column(length = 128) private String categoryName;
    @Column(length = 512) private String controlArea;
    @Column(length = 2000) private String mainParameters;
    @Column(length = 256) private String supplier;
    @Column private Long productionDate;

    @Column(length = 512)
    private String specification;

    @Column(length = 256)
    private String model;

    @Column(length = 256)
    private String manufacturer;

    @Column(length = 128)
    private String brand;

    @Column(length = 512)
    private String location;

    @Column(precision = 20, scale = 6)
    private java.math.BigDecimal quantity;

    @Column(length = 32)
    private String unit;

    @Column private Long installationDate;
    @Column private Long commissionDate;
    @Column private Long warrantyEndDate;

    @Column(length = 4000)
    private String remark;

    // All legacy electrical, lift, certificate and spare fields remain lossless in properties.
    @Column
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    private Map<String, String> properties = Map.of();
}
