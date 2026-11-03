package org.jetlinks.pro.patrol.entity;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.api.crud.entity.GenericEntity;

import javax.persistence.Column;
import javax.persistence.Table;

/** 计划+业务日期的生成封印；与当日全部任务同事务插入，规则编辑不能隐式重建已生成日期。 */
@Getter
@Setter
@Table(name = "pt_generation")
public class PatrolGenerationEntity extends GenericEntity<String> {
    @Column(length = 64, nullable = false, updatable = false)
    private String planId;

    @Column(length = 10, nullable = false, updatable = false)
    private String businessDate;
}
