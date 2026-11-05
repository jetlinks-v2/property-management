package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;

/** 规则变更与审计同事务，保存前后快照和修改原因，不开放日志写入/删除接口。 */
@Getter
@Setter
@Table(
        name = "pm_fin_document_rule_log",
        indexes = @Index(name = "idx_pm_fin_doc_rule_log", columnList = "projectId"))
public class DocumentRuleLogEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 500, nullable = false)
    private String reason;

    @Column(length = 4000)
    private String beforeSnapshot;

    @Column(length = 4000, nullable = false)
    private String afterSnapshot;
}
