package org.jetlinks.pro.property.finance.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.*;

/** 项目开票规则，主键即项目ID；默认规则只读返回，不在查询时写库。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "pm_fin_document_rule")
public class DocumentRuleEntity extends FinanceEntity {
    @Column(length = 32, nullable = false)
    private String timeLimitType;

    @Column private Integer limitDays;
    @Column private Integer maxReopenTimes;
}
