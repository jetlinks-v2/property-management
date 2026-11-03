package org.jetlinks.pro.property.base.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import javax.persistence.Column;

/** 物业自有档案的版本、逻辑删除和最后修改人；不参与 SaaS 镜像覆盖。 */
@Getter
@Setter
public abstract class ArchiveEntity extends GenericEntity<String> {
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
