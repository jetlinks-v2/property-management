package org.jetlinks.pro.property.finance.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;

import javax.persistence.*;

/** 单个号码状态。活动键跨票本唯一；逻辑删除时释放键，保留原号码和全部历史。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
        name = "pm_fin_allocation_number",
        indexes = {
            @Index(name = "uk_pm_fin_alloc_active", columnList = "activeKey", unique = true),
            @Index(name = "idx_pm_fin_alloc_number_book", columnList = "bookId,deleted,number")
        })
public class AllocationNumberEntity extends FinanceEntity {
    @Column(length = 64, nullable = false)
    private String bookId;

    @JsonIgnore
    @Column(length = 64)
    private String activeKey;

    @Column(nullable = false)
    private Long number;

    @Column(nullable = false)
    private Boolean used;

    @Column(nullable = false)
    private Boolean enabled;
}
