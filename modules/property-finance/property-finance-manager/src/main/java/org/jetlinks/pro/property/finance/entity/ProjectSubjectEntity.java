package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 项目仅引用共享科目；唯一业务键包含已删除关系，显式重新下发恢复同一稳定关系。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_project_subject",
        indexes =
                @Index(
                        name = "uk_pm_fin_project_subject",
                        columnList = "projectId,subjectId",
                        unique = true))
public class ProjectSubjectEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String subjectId;
}
