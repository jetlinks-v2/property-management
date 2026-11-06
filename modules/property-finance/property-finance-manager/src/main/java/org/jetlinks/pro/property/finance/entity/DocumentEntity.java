package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.math.BigDecimal;

import javax.persistence.*;

/** 独立票据事实。发票仅模拟，红票另存负金额；不开放改金额或删除入口。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_document",
        indexes = {
            @Index(name = "idx_pm_fin_doc_project", columnList = "projectId,documentType,issuedAt"),
            @Index(name = "uk_pm_fin_doc_number", columnList = "documentNumber", unique = true),
            @Index(
                    name = "uk_pm_fin_doc_previous",
                    columnList = "previousDocumentId",
                    unique = true)
        })
public class DocumentEntity extends FinanceEntity {
    @JsonIgnore
    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String creatorId;

    @Column(length = 16, nullable = false)
    private String documentType;

    @Column(length = 32, nullable = false)
    private String channel;

    @Column(length = 32, nullable = false)
    private String status;

    @Column(length = 80, nullable = false)
    private String documentNumber;

    @Column(length = 64)
    private String originalDocumentId;

    @Column(length = 64)
    private String reverseDocumentId;

    /** 蓝票重开链与红票反向关联分别保存，避免把新蓝票误当成红票。 */
    @Column(length = 64, nullable = false)
    private String rootDocumentId;

    @Column(length = 64)
    private String previousDocumentId;

    @Column(length = 64)
    private String reopenDocumentId;

    @Column(nullable = false)
    private Integer reopenCount;

    @Column(length = 64, nullable = false)
    private String companyId;

    @Column(length = 100, nullable = false)
    private String companyName;

    @Column(length = 50, nullable = false)
    private String sellerTaxNum;

    @Column(length = 64, nullable = false)
    private String customerId;

    @Column(length = 255, nullable = false)
    private String customerName;

    /** 开具时的购方抬头，与来源客户名称分别冻结，支持历史列表检索。 */
    @Column(length = 255, nullable = false)
    private String buyerName;

    @Column(length = 4)
    private String invoiceType;

    @Column(length = 32, nullable = false)
    private String issueScene;

    @Column(length = 30, nullable = false)
    private String issuedAt;

    /** 票据核销仅作核验登记；历史空值按未核销处理，不修改资金核销或票据金额。 */
    @Column private Boolean verified = false;

    @Column(length = 30)
    private String verifiedAt;

    @Column(length = 64)
    private String verifiedBy;

    @Column(length = 128)
    private String verifiedName;

    @Column(length = 200)
    private String verificationRemark;

    @Column(length = 1000)
    private String remark;

    @JsonIgnore
    @Column(length = 64, nullable = false)
    private String requestFingerprint;

    @JsonIgnore
    @Column(length = 8000, nullable = false)
    private String buyerJson;

    @JsonIgnore
    @Column(length = 4000, nullable = false)
    private String ruleJson;

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
