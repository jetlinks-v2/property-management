package org.jetlinks.pro.property.base.entity;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import javax.persistence.Column;
import javax.persistence.Table;

/** 全局字典分类，编码保存后不可变；删除保留身份，防止历史编码被重新解释。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "pm_dictionary_type")
public class PropertyDictionaryTypeEntity extends ArchiveEntity {
    @Column(length = 64, nullable = false)
    private String code;

    @Column(length = 128, nullable = false)
    private String name;

    @Column(nullable = false)
    private Boolean enabled;

    @Column private Long sortIndex;

    @Column(length = 512)
    private String description;
}
