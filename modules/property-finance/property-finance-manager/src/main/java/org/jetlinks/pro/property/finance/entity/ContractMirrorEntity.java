package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;

/** 财务拥有的资管合同镜像；来源身份及编号不改写历史应收归属。 */
@Getter
@Setter
@Table(
        name = "pm_fin_contract",
        indexes = {
            @Index(name = "uk_pm_fin_contract_code", columnList = "codeKey", unique = true),
            @Index(name = "idx_pm_fin_contract_project", columnList = "projectId,deleted")
        })
public class ContractMirrorEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    @JsonIgnore
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 128, nullable = false)
    private String sourceId;

    @Column(length = 128, nullable = false)
    private String code;

    @Column(length = 64, nullable = false)
    @JsonIgnore
    private String codeKey;

    @Column(length = 255, nullable = false)
    private String name;

    @Column(length = 64, nullable = false)
    private String spaceId;

    @Column(length = 2000, nullable = false)
    private String fullName;

    @Column(length = 1000, nullable = false)
    private String customerId;

    @Column(length = 1000, nullable = false)
    private String customerName;

    @Column(length = 10, nullable = false)
    private String startDate;

    @Column(length = 10, nullable = false)
    private String endDate;

    @Column(length = 32, nullable = false)
    private String leaseArea;

    @Column(length = 64, nullable = false)
    @JsonIgnore
    private String fingerprint;
}
