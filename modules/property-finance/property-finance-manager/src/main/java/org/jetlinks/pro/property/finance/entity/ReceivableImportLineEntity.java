package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 历史导入暂存明细；审核通过前不进入应收、收银或统计，驳回后保留原始快照。 */
@Getter
@Setter
@Table(
        name = "pm_fin_recv_import_line",
        indexes = {
            @Index(
                    name = "uk_pm_fin_recv_imp_row",
                    columnList = "importId,lineNumber",
                    unique = true),
            @Index(name = "idx_pm_fin_recv_imp_scope", columnList = "projectId,spaceId,status")
        })
public class ReceivableImportLineEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String spaceId;

    @Column(length = 64, nullable = false)
    private String importId;

    @Column(length = 2000, nullable = false)
    private String fullName;

    @Column(length = 255, nullable = false)
    private String customerName;

    @Column(nullable = false)
    private Integer lineNumber;

    @Column(length = 16, nullable = false)
    private String status;

    @JsonIgnore
    @Column(length = 100000, nullable = false)
    private String payloadJson;

    @Column(length = 1000)
    private String reviewReason;

    @Column(length = 64)
    private String reviewedBy;

    @Column(length = 128)
    private String reviewedName;

    @Column private Long reviewedAt;
}
