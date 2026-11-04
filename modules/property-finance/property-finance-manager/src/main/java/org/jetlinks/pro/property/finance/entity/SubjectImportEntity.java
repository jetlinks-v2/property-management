package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Table;

/** 科目导入批次独立于科目写入事务，失败也必须保留来源、数量和操作者。 */
@Getter
@Setter
@Table(name = "pm_fin_subject_import")
public class SubjectImportEntity extends FinanceEntity {
    @Column(length = 255, nullable = false)
    private String fileName;

    @Column(length = 32, nullable = false)
    private String status;

    @Column private Integer totalCount;

    @Column(nullable = false)
    private Integer successCount;

    @Column(nullable = false)
    private Integer errorCount;

    @Column(length = 8000)
    private String errorMessage;

    @Column private Long finishTime;
}
