package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.math.BigDecimal;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 每个来源与支付方式组合独立保存；activeKey 只由服务端生成，逻辑删除后释放唯一身份。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_recorded",
        indexes = {
            @Index(name = "uk_pm_fin_recorded_active", columnList = "activeKey", unique = true),
            @Index(name = "idx_pm_fin_recorded_project", columnList = "projectId")
        })
public class RecordedInfoEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String companyId;

    @Column(length = 64, nullable = false)
    private String bankId;

    @Column(length = 100, nullable = false)
    private String sourceCharge;

    @Column(length = 100, nullable = false)
    private String patternPayment;

    @Column(length = 100)
    private String sourceChargeText;

    @Column(length = 100)
    private String patternPaymentText;

    @Column(length = 100)
    private String companyName;

    @Column(length = 100)
    private String bankName;

    @Column(length = 64)
    private String facilitatorId;

    @Column private Integer serviceChargeType;

    @Column(precision = 18, scale = 2)
    private BigDecimal minimumFee;

    @Column(precision = 18, scale = 2)
    private BigDecimal maximumFee;

    @Column(precision = 18, scale = 2)
    private BigDecimal fixedAmount;

    @Column(length = 64)
    private String activeKey;
}
