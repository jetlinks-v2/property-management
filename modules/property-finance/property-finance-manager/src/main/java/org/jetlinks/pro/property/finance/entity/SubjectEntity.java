package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 共享收费科目；编码只在创建时由后端生成，项目关系实时解析主档，账单另存历史快照。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_subject",
        indexes = {
            @Index(name = "uk_pm_fin_subject_code", columnList = "code", unique = true),
            @Index(
                    name = "uk_pm_fin_subject_name",
                    columnList = "categoryId,nameKey",
                    unique = true)
        })
public class SubjectEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String categoryId;

    @Column(length = 20, nullable = false)
    private String code;

    @Column(length = 100, nullable = false)
    private String name;

    @Column(length = 100)
    private String nameKey;

    @Column(length = 64, nullable = false)
    private String feeType;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(nullable = false)
    private Boolean autoInvoice;

    @Column(length = 64, nullable = false)
    private String arrearsConfigId;

    @Column(length = 250)
    private String remark;
}
