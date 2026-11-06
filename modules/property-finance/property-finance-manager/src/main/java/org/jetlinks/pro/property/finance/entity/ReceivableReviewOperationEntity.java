package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.*;

/** 一批按钮审核的不可变成功身份与结果，不能通过通用CRUD覆盖或删除。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_recv_review",
        indexes =
                @Index(
                        name = "idx_pm_fin_recv_review",
                        columnList = "tenantId,projectId,createTime"))
public class ReceivableReviewOperationEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    @JsonIgnore
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    @JsonIgnore
    private String requestFingerprint;

    @Column(length = 16, nullable = false)
    private String action;

    // 100 笔各 1000 字原因，包含 JSON 转义后的上界。
    @Column(length = 1000000, nullable = false)
    @JsonIgnore
    private String resultJson;
}
