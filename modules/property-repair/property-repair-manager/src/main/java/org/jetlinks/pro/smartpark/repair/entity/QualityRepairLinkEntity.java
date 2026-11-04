package org.jetlinks.pro.smartpark.repair.entity;
import lombok.Getter;import lombok.Setter;import javax.persistence.*;import org.hswebframework.web.api.crud.entity.GenericEntity;import org.hswebframework.web.crud.annotation.EnableEntityEvent;
/** 来源键由可信租户、品质应用和检查记录计算；插入与报修创建同事务，数据库唯一键抵御并发重试。 */
@Getter @Setter @EnableEntityEvent @Table(name="sp_repair_quality_link")
public class QualityRepairLinkEntity extends GenericEntity<String> {
 @Column(length=64,nullable=false,updatable=false)private String tenantId;
 @Column(length=64,nullable=false,updatable=false)private String sourceApplicationId;
 @Column(length=64,nullable=false,updatable=false)private String sourceRecordId;
 @Column(length=64,nullable=false,updatable=false)private String targetApplicationId;
 @Column(length=64)private String orderId;
}
