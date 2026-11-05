package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.math.BigDecimal;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 公司共享税目；项目通过有效公司关联访问，历史账单使用独立税务快照。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_tax_item",
        indexes =
                @Index(
                        name = "uk_pm_fin_tax_match",
                        columnList = "companyId,matchKey",
                        unique = true))
public class TaxItemEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String companyId;

    @Column(length = 50, nullable = false)
    private String sellerTaxNum;

    @Column(length = 100, nullable = false)
    private String companyName;

    @Column(length = 64, nullable = false)
    @JsonIgnore
    private String matchKey;

    @Column(length = 255, nullable = false)
    private String goodsName;

    @Column(length = 100)
    private String goodsShortName;

    @Column(length = 64, nullable = false)
    private String goodsCode;

    @Column(length = 255, nullable = false)
    private String classificationName;

    @Column(length = 255)
    private String specAndTypes;

    @Column(precision = 8, scale = 6, nullable = false)
    private BigDecimal taxRate;

    @Column(length = 50)
    private String unit;

    @com.fasterxml.jackson.databind.annotation.JsonSerialize(
            using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    @Column(precision = 23, scale = 10)
    private BigDecimal price;

    @Column(nullable = false)
    private Integer includingTaxFlag;

    @Column(nullable = false)
    private Integer favouredPolicyFlag;

    @Column(length = 255)
    private String favouredPolicyName;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(length = 128)
    private String goodsId;

    @Column(length = 32)
    private String syncStatus;

    @Column private Long lastSyncTime;

    @Column(length = 1000)
    private String lastSyncError;
}
