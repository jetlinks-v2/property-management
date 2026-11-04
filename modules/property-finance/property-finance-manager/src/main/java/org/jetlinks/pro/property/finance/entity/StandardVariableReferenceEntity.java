package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.api.crud.entity.GenericEntity;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 当前标准的精确变量引用，删除保护不使用 JSON 文本模糊匹配。 */
@Getter
@Setter
@Table(
        name = "pm_fin_standard_variable",
        indexes = {
            @Index(name = "uk_pm_fin_std_var", columnList = "standardId,variableId", unique = true),
            @Index(name = "idx_pm_fin_var_ref", columnList = "variableId")
        })
public class StandardVariableReferenceEntity extends GenericEntity<String> {
    @Column(length = 64, nullable = false)
    private String standardId;

    @Column(length = 64, nullable = false)
    private String variableId;
}
