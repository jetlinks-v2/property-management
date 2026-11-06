package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.*;

/** 自动结转计划的项目科目占用；停用仍保留，移除或逻辑删除时释放唯一键并保留原记录。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_carry_subject",
        indexes = {
            @Index(
                    name = "uk_pm_fin_carry_subject",
                    columnList = "projectId,subjectId,activeKey",
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

    @Column(length = 16)
    private String activeKey;
}
