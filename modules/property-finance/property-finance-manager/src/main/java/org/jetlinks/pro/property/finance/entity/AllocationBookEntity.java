package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import java.math.BigDecimal;
import java.time.LocalDate;

import javax.persistence.*;

/** 项目领用票本。范围/名称是分配快照；号码唯一性由票据池约束，电子收据开具不消费此池。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_allocation_book",
        indexes = {
            @Index(
                    name = "idx_pm_fin_alloc_book_scope",
                    columnList = "tenantId,projectId,createTime")
        })
public class AllocationBookEntity extends FinanceEntity {
    @JsonIgnore
    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String batchNumber;

    @Column(nullable = false)
    private LocalDate buyDate;

    @Column(length = 64, nullable = false)
    private String recipientId;

    @Column(length = 128, nullable = false)
    private String recipientUsername;

    @Column(length = 128, nullable = false)
    private String recipientName;

    @Column(length = 64, nullable = false)
    private String billType;

    @Column(length = 128, nullable = false)
    private String billTypeName;

    @Column(length = 255, nullable = false)
    private String billCode;

    @Column(length = 64)
    private String billSource;

    @Column(length = 128)
    private String billSourceName;

    @JsonSerialize(using = ToStringSerializer.class)
    @Column(precision = 18, scale = 2)
    private BigDecimal quota;

    @Column(nullable = false)
    private Long startNumber;

    @Column(nullable = false)
    private Long endNumber;

    @Column(nullable = false)
    private Integer numberCount;

    @Column(nullable = false)
    private Integer usedCount;

    @Column(nullable = false)
    private Integer discardedCount;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(nullable = false)
    private Boolean recovered;

    @Column(length = 255)
    private String remark;

    @Column(length = 64, nullable = false)
    private String creatorId;

    @Column(length = 128, nullable = false)
    private String creatorName;
}
