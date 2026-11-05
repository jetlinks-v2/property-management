package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;

/** 导入批次独立留存，失败不随账单事务回滚；不存储Excel二进制或异常堆栈。 */
@Getter
@Setter
@Table(
        name = "pm_fin_recv_import",
        indexes = {
            @Index(name = "idx_pm_fin_recv_import_project", columnList = "projectId,createTime")
        })
public class ReceivableImportEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 255, nullable = false)
    private String fileName;

    @Column(length = 20, nullable = false)
    private String status;

    @Column private Integer totalCount;
    @Column private Integer successCount;
    @Column private Integer errorCount;

    @Column(length = 8000)
    private String errorMessage;

    @Column(length = 64)
    private String operatorId;

    @Column(length = 128)
    private String operatorName;

    @Column private Long finishTime;
}
