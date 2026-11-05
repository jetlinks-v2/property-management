package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.math.BigDecimal;

import javax.persistence.*;

/** 预收/押金余额；业务键稳定，余额只能与不可变资金流水一并变动。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_fund_account",
        indexes = {
            @Index(name = "idx_pm_fin_fund_scope", columnList = "projectId,spaceId"),
            @Index(name = "idx_pm_fin_fund_refs", columnList = "companyId,subjectId,standardId")
        })
public class FundAccountEntity extends FinanceEntity {
    @Transient @com.fasterxml.jackson.annotation.JsonIgnore private String sourceIdentity;

    /** 本次存入的可信资料，随流水冻结；不落入可变账户，也不接收客户端覆盖。 */
    @Transient @com.fasterxml.jackson.annotation.JsonIgnore private String documentSnapshotJson;

    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String companyId;

    @Column(length = 100, nullable = false)
    private String companyName;

    @Column(length = 20, nullable = false)
    private String type;

    @Column(length = 64, nullable = false)
    private String spaceId;

    @Column(length = 2000, nullable = false)
    private String fullName;

    @Column(length = 64, nullable = false)
    private String customerId;

    @Column(length = 64, nullable = false)
    private String relationshipId;

    @Column(length = 128, nullable = false)
    private String customerName;

    @Column(length = 64, nullable = false)
    private String subjectId;

    @Column(length = 100, nullable = false)
    private String subjectName;

    @Column(length = 64, nullable = false)
    private String standardId;

    @Column(length = 100, nullable = false)
    private String standardName;

    @Column(precision = 20, scale = 2, nullable = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal balance;

    @Column(precision = 20, scale = 2, nullable = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal invoiceOccupiedAmount;
}
