package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Table;

/** 文件导入批次保留来源、数量和失败原因；成功数量只能在整批事务提交后更新。 */
@Getter
@Setter
@Table(name = "pm_fin_tax_import")
public class TaxImportEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String companyId;

    @Column(length = 255, nullable = false)
    private String fileName;

    @Column(length = 32, nullable = false)
    private String status;

    @Column private Integer totalCount;
    @Column private Integer successCount;
    @Column private Integer errorCount;

    @Column(length = 8000)
    private String errorMessage;

    @Column private Long finishTime;
}
