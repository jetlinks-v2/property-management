package org.jetlinks.pro.patrol.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.DateTimeCodec;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.jetlinks.pro.assets.annotation.Asset;

import java.time.LocalDate;

import javax.persistence.Column;
import javax.persistence.Table;

/** 巡更排班配置；只保存业务关系，身份与授权仍由 SaaS 管理。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "pt_roster", indexes = @javax.persistence.Index(name = "pt_roster_project", columnList = "scopeKey,projectId,ownerOrganizationId"))
@Asset(type = "patrolRoster")
public class PatrolRosterEntity extends PatrolConfigEntity {
    @Column(length = 64, nullable = false)
    @NotBlank
    private String postId;

    @Column(length = 10, nullable = false)
    @NotNull
    @DateTimeCodec(format = "yyyy-MM-dd")
    @ColumnType(javaType = String.class)
    private LocalDate businessDate;

    @Column(length = 64, nullable = false)
    @NotBlank
    private String userId;

    @Column(length = 128)
    private String userName;

    @Column(length = 128)
    private String shiftName;

    /** 原排班的“休息”行保留展示，但不能成为在班资格。 */
    @Column(nullable = false)
    private boolean rest;

    @Column(nullable = false)
    @NotNull
    private Long startTime;

    @Column(nullable = false)
    @NotNull
    private Long endTime;
}
