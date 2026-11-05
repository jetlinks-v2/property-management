package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.*;

/** 审批通过后参与算费，按版本冻结规则与客户；停止或删除不级联财务历史。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_house_setting",
        indexes = {
            @Index(
                    name = "idx_pm_fin_set_scope",
                    columnList = "projectId,spaceId,subjectId,deleted"),
            @Index(name = "idx_pm_fin_set_standard", columnList = "standardId,deleted"),
            @Index(name = "idx_pm_fin_set_review", columnList = "projectId,reviewState,deleted")
        })
public class HouseSettingEntity extends FinanceEntity {
    /** 后台恢复原租户上下文；空串明确表示平台域，不从最近请求借用用户上下文。 */
    @JsonIgnore
    @Column(length = 64)
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String spaceId;

    @Column(length = 2000, nullable = false)
    private String fullName;

    @Column(length = 64, nullable = false)
    private String projectSubjectId;

    @Column(length = 64, nullable = false)
    private String subjectId;

    @Column(length = 100, nullable = false)
    private String subjectName;

    @Column(length = 64, nullable = false)
    private String standardId;

    @Column(length = 60, nullable = false)
    private String standardCode;

    @Column(length = 30, nullable = false)
    private String standardName;

    @Column(length = 64, nullable = false)
    private String customerRelationshipId;

    @Column(length = 64, nullable = false)
    private String customerId;

    @Column(length = 128, nullable = false)
    private String customerName;

    @Column(length = 10, nullable = false)
    private String effectiveStartDate;

    @Column(length = 10)
    private String effectiveEndDate;

    @Column(length = 20, nullable = false)
    private String status;

    /** 设置审批结果；通过后生成的普通账单直接继承该结果。 */
    @Column(length = 16, nullable = false)
    private String reviewState = "PENDING";

    @Column(length = 64)
    private String reviewedBy;

    @Column(length = 128)
    private String reviewedName;

    @Column private Long reviewedAt;

    @Column(length = 1000)
    private String reviewReason;

    @Column(nullable = false)
    private Integer configVersion;

    @Column(length = 64)
    private String previousVersionId;

    @Column(length = 300)
    private String remark;

    @JsonIgnore
    @Column(length = 60000, nullable = false)
    private String snapshotJson;
}
