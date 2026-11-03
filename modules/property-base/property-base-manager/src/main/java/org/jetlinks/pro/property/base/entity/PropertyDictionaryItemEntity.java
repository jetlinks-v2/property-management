package org.jetlinks.pro.property.base.entity;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import javax.persistence.Column;
import javax.persistence.Table;

/** 分类内的稳定选项值与可编辑文案；启停独立于分类，历史资料保留原值。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "pm_dictionary_item")
public class PropertyDictionaryItemEntity extends ArchiveEntity {
    @Column(length = 64, nullable = false)
    private String typeCode;

    @Column(length = 64, nullable = false)
    private String value;

    @Column(length = 128, nullable = false)
    private String text;

    @Column(nullable = false)
    private Boolean enabled;

    @Column private Long sortIndex;

    @Column(length = 512)
    private String description;
}
