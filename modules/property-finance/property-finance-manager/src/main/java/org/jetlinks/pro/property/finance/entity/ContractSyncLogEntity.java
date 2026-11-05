package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;

/** 与镜像及同步版本同事务的批次审计；不记录上游URL、认证或原始个人资料。 */
@Getter
@Setter
@Table(name = "pm_fin_contract_sync_log")
public class ContractSyncLogEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(nullable = false)
    private Integer added;

    @Column(nullable = false)
    private Integer updated;

    @Column(nullable = false)
    private Integer removed;

    @Column(nullable = false)
    private Integer unchanged;
}
