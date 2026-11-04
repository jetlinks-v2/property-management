package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.*;

/** 应收业务变更审计，与账单写入同事务，保留删除前后身份；不开放修改/删除接口。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_receivable_log",
        indexes = {
            @Index(name = "idx_pm_fin_recv_log", columnList = "projectId,receivableId,createTime")
        })
public class ReceivableLogEntity extends GenericEntity<String> {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String receivableId;

    @Column(length = 64, nullable = false)
    private String spaceId;

    @Column(length = 30, nullable = false)
    private String operation;

    @Column(length = 1000, nullable = false)
    private String reason;

    @Column(length = 64, nullable = false)
    private String operatorId;

    @Column(length = 128, nullable = false)
    private String operatorName;

    @Column(nullable = false)
    private Long createTime;

    @Column(nullable = false)
    private Long beforeVersion;

    @Column(nullable = false)
    private Long afterVersion;

    @JsonIgnore
    @Column(length = 60000, nullable = false)
    private String beforeJson;

    @JsonIgnore
    @Column(length = 60000, nullable = false)
    private String afterJson;

    @JsonIgnore
    @Column(length = 60000)
    private String detailJson;
}
