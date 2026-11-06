package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.*;

/** 不可变柜台报价；不占用应收或活动资格，不开放通用编辑和删除接口。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_prepay_quote",
        indexes = {
            @Index(name = "idx_pm_fin_prepay_scope", columnList = "projectId,spaceId,createTime")
        })
public class PrepayQuoteEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String spaceId;

    @Column(length = 64, nullable = false)
    private String settingId;

    @Column(length = 64)
    private String activityId;

    @Column(length = 64, nullable = false)
    @JsonIgnore
    private String requestFingerprint;

    @Column(length = 64, nullable = false)
    @JsonIgnore
    private String creatorId;

    @Column(length = 20, nullable = false)
    private String status;

    @Column(nullable = false)
    private Long expiresAt;

    @Column(length = 64)
    private String paymentId;

    @Column(length = 64)
    @JsonIgnore
    private String confirmationFingerprint;

    @Column(length = 6000000, nullable = false)
    @JsonIgnore
    private String snapshotJson;
}
