package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;

/** 后台导入任务。文件在持久数据卷，数据库CAS租约防止多实例重复执行，认证快照不暴露。 */
@Getter
@Setter
@Table(
        name = "pm_fin_import_task",
        indexes = {
            @Index(name = "idx_pm_fin_import_task_scan", columnList = "state,leaseUntil"),
            @Index(name = "uk_pm_fin_import_task_source", columnList = "sourceKey", unique = true)
        })
public class FinanceImportTaskEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 16, nullable = false)
    private String kind;

    @Column(length = 16, nullable = false)
    private String operation;

    @Column(length = 24, nullable = false)
    private String state;

    @Column(length = 64, nullable = false)
    private String sourceKey;

    @Column(length = 64)
    private String recordId;

    @Column(length = 64)
    private String tenantId;

    @Column(length = 60000)
    @JsonIgnore
    private String executorSnapshot;

    @Column(length = 255)
    @JsonIgnore
    private String fileName;

    @Column(length = 64)
    private String leaseOwner;

    @Column private Long leaseUntil;

    @Column(length = 1000)
    private String reason;

    @Column(length = 8000)
    private String errorMessage;
}
