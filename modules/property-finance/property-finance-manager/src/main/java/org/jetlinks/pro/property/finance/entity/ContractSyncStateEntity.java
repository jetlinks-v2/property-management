package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;

/** 项目合同增量应用版本；下载在事务外，提交时行锁+CAS拒绝较旧下载覆盖较新镜像。 */
@Getter
@Setter
@Table(name = "pm_fin_contract_sync")
public class ContractSyncStateEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String projectId;
}
