package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.*;

/** 按钮审批的活动设置，当前配置与历史消费快照分别保存。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_activity",
        indexes =
                @Index(
                        name = "idx_pm_fin_activity_project",
                        columnList = "projectId,deleted,standardId"))
public class ActivityEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 100, nullable = false)
    private String name;

    @Column(length = 64, nullable = false)
    private String subjectId;

    @Column(length = 64, nullable = false)
    private String standardId;

    @Column(length = 10, nullable = false)
    private String startDate;

    @Column(length = 10, nullable = false)
    private String endDate;

    @Column(nullable = false)
    private Boolean enabled;

    /** 新建和编辑后重新待审，审批结果不由客户端配置字段提供。 */
    @Column(length = 16, nullable = false)
    private String reviewState = "PENDING";

    @Column(length = 64)
    private String reviewedBy;

    @Column(length = 128)
    private String reviewedName;

    @Column private Long reviewedAt;

    @Column(length = 1000)
    private String reviewReason;

    @JsonIgnore
    @Column(length = 1000000, nullable = false)
    private String configJson;
}
