package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.*;

/** 会计期间的永久来源归属；不可变资金/票据不因月结被改写金额或快照。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_month_member",
        indexes =
                @Index(
                        name = "uk_pm_fin_month_member",
                        columnList = "operationId,sourceType,sourceId",
                        unique = true))
public class MonthClosingMemberEntity extends FinanceEntity {
    @JsonIgnore
    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String operationId;

    @Column(length = 16, nullable = false)
    private String sourceType;

    @Column(length = 64, nullable = false)
    private String sourceId;

    @Column(nullable = false)
    private Long sourceVersion;

    @Column private Boolean previousClosed;

    @JsonIgnore
    @Column(length = 1000000, nullable = false)
    private String spaceIdsJson;

    @JsonIgnore
    @Column(length = 6000, nullable = false)
    private String detailJson;
}
