package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 项目关联独立于共享公司；默认项占用项目唯一键，普通关联 defaultKey 为 null。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_project_company",
        indexes = {
            @Index(
                    name = "uk_pm_fin_project_company",
                    columnList = "projectId,companyId",
                    unique = true),
            @Index(
                    name = "uk_pm_fin_project_company_default",
                    columnList = "defaultKey",
                    unique = true)
        })
public class FinanceProjectCompanyEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String companyId;

    @Column(length = 64)
    private String defaultKey;
}
