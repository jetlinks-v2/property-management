package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 用户维护的收费目录，父级创建后固定；不限制层数，也不绑定 SaaS 组织层级。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_subject_category",
        indexes =
                @Index(
                        name = "uk_pm_fin_category_name",
                        columnList = "parentId,nameKey",
                        unique = true))
public class SubjectCategoryEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String parentId;

    @Column(length = 100, nullable = false)
    private String name;

    @Column(length = 100)
    private String nameKey;

    @Column(nullable = false)
    private Integer sortIndex;
}
