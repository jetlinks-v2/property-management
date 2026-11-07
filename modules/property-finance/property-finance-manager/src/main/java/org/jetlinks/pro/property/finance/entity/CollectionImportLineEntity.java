package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;

/** 待审收款行，外部收款编号在项目/类型内唯一，重传文件不会重复入账。 */
@Getter
@Setter
@Table(
        name = "pm_fin_collect_import_line",
        indexes = {
            @Index(name = "uk_pm_fin_collect_imp_source", columnList = "sourceKey", unique = true),
            @Index(
                    name = "uk_pm_fin_collect_imp_row",
                    columnList = "importId,lineNumber",
                    unique = true),
            @Index(name = "idx_pm_fin_collect_imp_scope", columnList = "projectId,spaceId,status")
        })
public class CollectionImportLineEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String spaceId;

    @Column(length = 64, nullable = false)
    private String importId;

    @Column(length = 64, nullable = false)
    private String sourceKey;

    @Column(length = 2000, nullable = false)
    private String fullName;

    @Column(length = 255, nullable = false)
    private String customerName;

    @Column(nullable = false)
    private Integer lineNumber;

    @Column(length = 16, nullable = false)
    private String status;

    @JsonIgnore
    @Column(length = 20000, nullable = false)
    private String payloadJson;

    @Column(length = 1000)
    private String reviewReason;

    @Column(length = 64)
    private String reviewedBy;

    @Column(length = 128)
    private String reviewedName;

    @Column private Long reviewedAt;

    @Column(length = 64)
    private String resultId;
}
