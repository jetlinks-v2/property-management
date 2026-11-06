package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 项目十进制变量定义；实际数值由后续房屋收费配置提供。 */
@Getter
@Setter
@Table(
        name = "pm_fin_formula_variable",
        indexes = {
            @Index(
                    name = "uk_pm_fin_var_code",
                    columnList = "projectId,variableCode",
                    unique = true),
            @Index(name = "uk_pm_fin_var_name", columnList = "projectId,nameKey", unique = true)
        })
public class FormulaVariableEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String variableCode;

    @Column(length = 100, nullable = false)
    private String variableName;

    @JsonIgnore
    @Column(length = 100, nullable = false)
    private String nameKey;

    @Column(length = 300)
    private String description;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(nullable = false)
    private Integer sort;
}
