package org.jetlinks.pro.property.quality.entity;
import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.util.*;
import java.math.BigDecimal;
import java.sql.JDBCType;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.jetlinks.pro.assets.annotation.Asset;
/** 品质Schedule持久化模型；归属与状态转换由对应应用服务维护。 */
@Getter @Setter @EnableEntityEvent
@Table(name="pq_schedule",indexes={@Index(name="idx_pq_schedule_due",columnList="status,scheduledTime"),@Index(name="uk_pq_schedule_slot",columnList="taskId,scheduledTime",unique=true)})
public class QualityScheduleEntity extends QualityEntity {
 @Column(length=128) private String taskId;
 @Column private Long scheduledTime;
 @Column private Long cycleStartTime;
 @Column private Long cycleEndTime;
 @Column private Integer status;
 @Column(length=128) private String workOrderId;
 @Column(length=8000) private String errorMessage;
}
