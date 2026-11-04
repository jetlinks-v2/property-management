package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.math.BigDecimal;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 共享税号/开票公司主档，税号全局唯一，项目默认关系不存放在主档中。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_company",
        indexes = @Index(name = "uk_pm_fin_company_tax", columnList = "taxNumber", unique = true))
public class FinanceCompanyEntity extends FinanceEntity {
    @Column(length = 100, nullable = false)
    private String companyName;

    @Column(length = 50, nullable = false)
    private String taxNumber;

    @Column(length = 255, nullable = false)
    private String address;

    @Column(length = 15, nullable = false)
    private String phone;

    @Column(length = 100, nullable = false)
    private String depositBank;

    @Column(length = 100, nullable = false)
    private String bankAccount;

    @Column(length = 60, nullable = false)
    private String drawer;

    @Column(length = 60, nullable = false)
    private String reviewer;

    @Column(length = 255, nullable = false)
    private String estateNo;

    @Column(length = 255)
    private String shop;

    @Column(length = 90)
    private String legalPersonId;

    @Column(length = 100)
    private String ratepayingNature;

    @Column(length = 60)
    private String invoiceManufacturerCode;

    @Column(length = 100)
    private String invoiceManufacturerName;

    @Column(length = 32)
    private String invoiceExtensionNumber;

    @Column(length = 64)
    private String departmentId;

    @Column(length = 64)
    private String thirdpartyVendorId;

    @com.fasterxml.jackson.databind.annotation.JsonSerialize(
            using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    @Column(precision = 19, scale = 6)
    private BigDecimal quota;
}
