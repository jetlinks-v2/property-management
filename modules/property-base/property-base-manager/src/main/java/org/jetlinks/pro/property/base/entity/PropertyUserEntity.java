package org.jetlinks.pro.property.base.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.jetlinks.pro.property.base.user.UserIdentity;

import javax.persistence.Column;
import javax.persistence.Table;
import java.util.ArrayList;
import java.util.List;

/** 主键复用 SaaS user 资产；来源同步与本地身份分别更新，禁止开放通用保存/删除。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "pm_user")
public class PropertyUserEntity extends GenericEntity<String> {
    @Column(length = 128, nullable = false)
    private String username;

    @Column(length = 128)
    private String name;

    @Column(length = 64)
    private String telephone;

    @Column(length = 256)
    private String email;

    @Column(length = 64, nullable = false)
    private String accountType;

    @Column(nullable = false)
    private Byte status;

    @Column private Long createTime;
    @Column private Long sourceModifyTime;
    @Column private Long sourceUpdateTime;

    @Column(nullable = false)
    @JsonIgnore
    private Boolean owner = false;

    @Column(nullable = false)
    @JsonIgnore
    private Boolean employee = false;

    @Column(nullable = false)
    private Long version = 0L;

    @Column(length = 64)
    private String modifierId;

    @Column(length = 128)
    private String modifierName;

    @Column private Long modifyTime;

    @Column(nullable = false)
    @JsonIgnore
    private Boolean deleted = false;

    @Column @JsonIgnore private Long deletedTime;

    private List<PropertyUserOrganizationEntity> orgList = List.of();

    public List<UserIdentity> getIdentities() {
        var values = new ArrayList<UserIdentity>(2);
        if (Boolean.TRUE.equals(owner)) values.add(UserIdentity.owner);
        if (Boolean.TRUE.equals(employee)) values.add(UserIdentity.employee);
        return values;
    }
}
