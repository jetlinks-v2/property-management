package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.math.BigDecimal;

import javax.persistence.*;

/** 票据每行冻结来源、税务和差额凭证；空间字段用于历史访问的资产授权。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_doc_line",
        indexes = {
            @Index(name = "uk_pm_fin_doc_line", columnList = "documentId,lineIndex", unique = true),
            @Index(name = "idx_pm_fin_doc_line_space", columnList = "projectId,spaceId")
        })
public class DocumentLineEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String documentId;

    @Column(nullable = false)
    private Integer lineIndex;

    @Column(length = 64, nullable = false)
    private String spaceId;

    @Column(length = 32, nullable = false)
    private String sourceType;

    @Column(length = 64, nullable = false)
    private String sourceId;

    @Column(length = 32, nullable = false)
    private String originalSourceType;

    @Column(length = 64, nullable = false)
    private String originalSourceId;

    @JsonIgnore
    @Column(length = 60000, nullable = false)
    private String sourceSnapshotJson;

    @JsonIgnore
    @Column(length = 20000, nullable = false)
    private String lineSnapshotJson;

    @JsonSerialize(using = ToStringSerializer.class)
    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal amount;

    @JsonSerialize(using = ToStringSerializer.class)
    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal taxAmount;

    @JsonSerialize(using = ToStringSerializer.class)
    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal netAmount;
}
