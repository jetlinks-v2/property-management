package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;

/** 后台报表任务，仅持有者可下载；文件不使用公开URL，授权在执行和下载时重新核验。 */
@Getter
@Setter
@Table(
        name = "pm_fin_export_task",
        indexes = @Index(name = "idx_pm_fin_export_task_state", columnList = "state,leaseUntil"))
public class FinanceExportTaskEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String ownerId;

    @Column(length = 64)
    @JsonIgnore
    private String tenantId;

    @Column(length = 20, nullable = false)
    private String report;

    @Column(length = 10, nullable = false)
    private String format;

    @Column(length = 20, nullable = false)
    private String state;

    @Column(length = 60000, nullable = false)
    @JsonIgnore
    private String inputJson;

    @Column(length = 60000, nullable = false)
    @JsonIgnore
    private String executorSnapshot;

    @Column(length = 64)
    @JsonIgnore
    private String leaseOwner;

    @Column private Long leaseUntil;

    @Column(length = 1000)
    private String errorMessage;
}
