package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.*;

/** 票据操作请求的不可变审计，也是未知响应恢复依据；与业务变更一同提交。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_doc_operation",
        indexes = {
            @Index(
                    name = "idx_pm_fin_doc_operation",
                    columnList = "projectId,documentId,createTime")
        })
public class DocumentOperationEntity extends FinanceEntity {
    @JsonIgnore
    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String documentId;

    @Column(length = 64, nullable = false)
    private String resultDocumentId;

    @Column(length = 16, nullable = false)
    private String documentType;

    @Column(length = 16, nullable = false)
    private String action;

    @JsonIgnore
    @Column(length = 64, nullable = false)
    private String requestFingerprint;

    @Column(length = 32)
    private String beforeState;

    @Column(length = 32, nullable = false)
    private String afterState;

    @Column(length = 1000)
    private String reason;
}
