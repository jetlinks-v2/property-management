package org.jetlinks.pro.property.base.entity;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import javax.persistence.Column;
import javax.persistence.Table;
import java.math.BigDecimal;

/** 末端空间的房屋业务扩展，主键复用空间 ID；删除只影响本表。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "pm_house")
public class PropertyHouseEntity extends ArchiveEntity {
    @Column(length = 64, nullable = false)
    private String projectId;

    @Column(length = 64, nullable = false)
    private String rootId;

    @Column(length = 64, nullable = false)
    private String houseType;

    @Column(length = 64, nullable = false)
    private String houseState;

    @Column(precision = 18, scale = 2, nullable = false)
    private BigDecimal billingArea;

    @Column(precision = 18, scale = 2)
    private BigDecimal buildingArea;

    @Column(precision = 18, scale = 2)
    private BigDecimal insideArea;

    @Column private Long deliveryDate;

    @Column(length = 2000)
    private String remark;
}
