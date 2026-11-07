package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.math.BigDecimal;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 项目收费规则的当前版本；历史计费调用方复制本版本的完整快照。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_standard",
        indexes = {
            @Index(name = "uk_pm_fin_std_code", columnList = "projectId,codeKey", unique = true),
            @Index(
                    name = "uk_pm_fin_std_name",
                    columnList = "projectId,subjectId,nameKey",
                    unique = true),
            @Index(name = "idx_pm_fin_std_tax", columnList = "taxItemId,deleted"),
            @Index(name = "idx_pm_fin_std_company", columnList = "companyId,deleted"),
            @Index(name = "idx_pm_fin_std_subject", columnList = "projectId,subjectId,deleted")
        })
public class StandardEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String subjectId;

    @Column(length = 64, nullable = false)
    private String categoryId;

    @Column(length = 100, nullable = false)
    private String subjectName;

    @Column(length = 60, nullable = false)
    private String standardCode;

    @Column(length = 30, nullable = false)
    private String standardName;

    @JsonIgnore
    @Column(length = 60, nullable = false)
    private String codeKey;

    @JsonIgnore
    @Column(length = 30, nullable = false)
    private String nameKey;

    @Column(length = 20, nullable = false)
    private String billingMethod;

    @Column(length = 4000, nullable = false)
    private String formulaExpression;

    @Column(length = 4000, nullable = false)
    private String formulaDisplay;

    @Column(nullable = false)
    private Long formulaVersion;

    @JsonSerialize(using = ToStringSerializer.class)
    @Column(precision = 20, scale = 2, nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private Integer chargeRoundType;

    @Column(nullable = false)
    private Integer mantissa;

    @Column(length = 20, nullable = false)
    private String settlementMethod;

    @Column(length = 64, nullable = false)
    private String taxItemId;

    @Column(length = 64, nullable = false)
    private String companyId;

    @Column(nullable = false)
    private Integer isDelay;

    @Column private Integer delayCalcForm;
    @Column private Integer delayRoundType;

    @JsonSerialize(using = ToStringSerializer.class)
    @Column(precision = 20, scale = 2)
    private BigDecimal rateAmount;

    @Column(length = 32)
    private String delayBaseType;

    @Column(length = 10)
    private String executeDate;

    @Column(length = 10)
    private String cancelDate;

    @Column(length = 300)
    private String remark;

    @JsonIgnore
    @Column(length = 20000, nullable = false)
    private String variablesSnapshotJson;

    @JsonIgnore
    @Column(length = 12000, nullable = false)
    private String taxSnapshotJson;
}
