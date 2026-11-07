package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.api.crud.entity.GenericEntity;

import javax.persistence.Column;

/** 财务本地数据统一保留版本、修改人和逻辑删除身份，不开放物理删除 CRUD。 */
@Getter
@Setter
public abstract class FinanceEntity extends GenericEntity<String> {
    @Column(nullable = false)
    private Long version;

    @Column(nullable = false)
    @JsonIgnore
    private Boolean deleted;

    @Column @JsonIgnore private Long deletedTime;
    @Column private Long createTime;
    @Column private Long modifyTime;

    @Column(length = 64)
    private String modifierId;

    @Column(length = 128)
    private String modifierName;
}
