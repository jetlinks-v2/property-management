package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 调整、日期拆分的本地审批申请；快照冻结申请内容，批准前不改正式账。 */
@Getter
@Setter
@Table(
        name = "pm_fin_recv_change",
        indexes = {
            @Index(name = "idx_pm_fin_recv_change_scope", columnList = "projectId,spaceId,status"),
            @Index(name = "idx_pm_fin_recv_change_bill", columnList = "receivableId,status")
        })
public class ReceivableChangeEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String spaceId;

    @Column(length = 64, nullable = false)
    private String receivableId;

    @Column(nullable = false)
    private Long sourceVersion;

    @Column(length = 2000, nullable = false)
    private String fullName;

    @Column(length = 255, nullable = false)
    private String customerName;

    @Column(length = 255, nullable = false)
    private String subjectName;

    @Column(length = 16, nullable = false)
    private String operation;

    @Column(length = 16, nullable = false)
    private String status;

    @Column(length = 1000, nullable = false)
    private String reason;

    @Column(length = 64, nullable = false)
    private String applicantId;

    @Column(length = 128, nullable = false)
    private String applicantName;

    @Column(length = 100000, nullable = false)
    @JsonIgnore
    private String inputJson;

    @Column(length = 100000, nullable = false)
    @JsonIgnore
    private String previewJson;

    @Column(length = 100000, nullable = false)
    @JsonIgnore
    private String beforeJson;

    @Column(length = 1000)
    private String reviewReason;

    @Column(length = 64)
    private String reviewedBy;

    @Column(length = 128)
    private String reviewedName;

    @Column private Long reviewedAt;
}
