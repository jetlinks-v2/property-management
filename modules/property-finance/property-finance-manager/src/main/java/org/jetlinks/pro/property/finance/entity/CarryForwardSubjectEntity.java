package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.*;

/** 自动结转计划的项目、科目、标准占用；停用仍保留，变更范围或逻辑删除时释放。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_carry_subject",
        indexes = {
            @Index(
                    name = "uk_pm_fin_carry_scope",
                    columnList = "projectId,subjectId,standardId,activeKey",
                    unique = true),
            @Index(name = "idx_pm_fin_carry_plan_subject", columnList = "planId,deleted")
        })
public class CarryForwardSubjectEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String planId;

    @Column(length = 64, nullable = false)
    private String subjectId;

    /** 历史已释放记录可为空；有效占用由已校验计划写入标准。 */
    @Column(length = 64)
    private String standardId;

    @Column(length = 16)
    private String activeKey;
}
