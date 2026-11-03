package org.jetlinks.pro.patrol.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.jetlinks.pro.assets.annotation.Asset;

import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 巡更点位配置；只保存业务关系，身份与授权仍由 SaaS 管理。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pt_point",
        indexes = @Index(name = "pt_point_scope_code", columnList = "scopeKey,code", unique = true))
@Asset(type = "patrolPoint")
public class PatrolPointEntity extends PatrolConfigEntity {
    @Column(length = 64, nullable = false)
    @NotBlank
    @Size(max = 64)
    private String code;

    @Column(length = 64, nullable = false)
    @NotBlank
    private String categoryId;

    @Column(length = 255)
    @Size(max = 255)
    private String location;

    @Column(length = 128)
    @Size(max = 128)
    private String nfcCode;
}
