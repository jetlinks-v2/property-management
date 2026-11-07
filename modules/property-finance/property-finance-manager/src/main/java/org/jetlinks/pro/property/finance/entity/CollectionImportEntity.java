package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;

/** 收款导入批次独立保留，任何校验失败均不留下财务草稿。 */
@Getter
@Setter
@Table(
        name = "pm_fin_collect_import",
        indexes = {
            @Index(
                    name = "idx_pm_fin_collect_imp_project",
                    columnList = "projectId,kind,createTime")
        })
public class CollectionImportEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 16, nullable = false)
    private String kind;

    @Column(length = 255, nullable = false)
    private String fileName;

    @Column(length = 20, nullable = false)
    private String status;

    @Column private Integer totalCount;
    @Column private Integer successCount;
    @Column private Integer rejectedCount;
    @Column private Integer errorCount;

    @Column(length = 8000)
    private String errorMessage;

    @Column(length = 64)
    private String operatorId;

    @Column(length = 128)
    private String operatorName;

    @Column private Long finishTime;

    @Column(length = 24)
    private String phase;

    @Column private Integer processedCount;
}
