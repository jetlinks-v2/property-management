package org.jetlinks.pro.property.base.entity;

import lombok.Getter;
import lombok.Setter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 项目内独立客户主档；同步用户仅提供稳定身份，不覆盖本地资料。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_customer",
        indexes =
                @Index(
                        name = "uk_pm_customer_user",
                        columnList = "projectId,userId",
                        unique = true))
public class PropertyCustomerEntity extends ArchiveEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String userId;

    @Column(length = 16, nullable = false)
    private String customerType;

    @Column(length = 128, nullable = false)
    private String name;

    @Column(length = 64, nullable = false)
    private String telephone;

    @Column(length = 256)
    private String email;

    @Column(length = 512)
    private String address;

    @Column(length = 64)
    private String certificateType;

    @Column(length = 128)
    private String certificateNumber;

    @Column(length = 128)
    private String creditCode;

    @Column(length = 128)
    private String legalRepresentative;

    @Column(length = 512)
    private String registeredAddress;

    @Override
    @JsonIgnore(false)
    public Boolean getDeleted() {
        return super.getDeleted();
    }
}
