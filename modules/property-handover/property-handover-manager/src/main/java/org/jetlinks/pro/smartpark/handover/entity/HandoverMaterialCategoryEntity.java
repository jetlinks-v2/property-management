package org.jetlinks.pro.smartpark.handover.entity;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import com.fasterxml.jackson.annotation.JsonIgnore;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.Transient;
import java.util.ArrayList;
import java.util.List;

/** 租户应用共享的两级资料分类，与按项目维护的问题分类分表。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "sp_handover_material_category", indexes = {
    @Index(name = "idx_ho_material_category_scope", columnList = "tenantId,applicationId,deleted"),
    @Index(name = "uk_ho_material_category_active", columnList = "activeKey", unique = true)
})
public class HandoverMaterialCategoryEntity extends HandoverApplicationEntity {
    @Column(length = 64) @JsonIgnore private String activeKey;
    @Column(length = 128, nullable = false) private String name;
    @Column(length = 64) private String parentId;
    @Column private Integer sortIndex;
    @Column(length = 2000) private String description;
    @Column private Boolean enabled;
    @Column(length = 128) private String modifierName;
    @Transient private String parentName;
    @Transient private List<HandoverMaterialCategoryEntity> children = new ArrayList<>();
}
