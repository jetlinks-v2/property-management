package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.*;

/** 每房屋每活动一次资格，只有收款成功事务能写入；退款保留历史资格，不靠换客户重复领取。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_prepay_consumption",
        indexes = {
            @Index(
                    name = "uk_pm_fin_prepay_house_activity",
                    columnList = "projectId,spaceId,activityId",
                    unique = true),
            @Index(name = "uk_pm_fin_prepay_consumed_quote", columnList = "quoteId", unique = true)
        })
public class PrepayConsumptionEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String spaceId;

    @Column(length = 64, nullable = false)
    private String activityId;

    @Column(length = 64, nullable = false)
    private String quoteId;

    @Column(length = 64, nullable = false)
    private String paymentId;

    @JsonIgnore
    @Column(length = 3000000, nullable = false)
    private String periodsJson;
}
