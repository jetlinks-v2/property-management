package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.math.BigDecimal;

import javax.persistence.*;

/** 票据来源占用事实；原额与逐实收行分配不修改，红冲/作废仅反向释放一次。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_document_occupation",
        indexes = {
            @Index(
                    name = "uk_pm_fin_document_source",
                    columnList = "documentId,sourceType,sourceId",
                    unique = true),
            @Index(
                    name = "idx_pm_fin_document_source",
                    columnList = "projectId,sourceType,sourceId")
        })
public class DocumentOccupationEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String documentId;

    @Column(length = 16, nullable = false)
    private String documentType;

    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String companyId;

    @Column(length = 64, nullable = false)
    private String spaceId;

    @Column(length = 32, nullable = false)
    private String sourceType;

    @Column(length = 64, nullable = false)
    private String sourceId;

    @Column(length = 16, nullable = false)
    private String state;

    @Column(precision = 20, scale = 2, nullable = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal amount;

    @Column(length = 65535, nullable = false)
    @JsonIgnore
    private String allocationJson;

    @Column(length = 500)
    private String releaseReason;

    @Column private Long releasedAt;
}
