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
/** 品质Log持久化模型；归属与状态转换由对应应用服务维护。 */
@Getter @Setter @EnableEntityEvent
@Table(name="pq_log",indexes={@Index(name="idx_pq_log_order",columnList="workOrderId,createTime")})
public class QualityLogEntity extends QualityEntity {
 @Column(length=128) private String workOrderId;
 @Column(length=128) private String taskId;
 @Column(length=128) private String rectificationId;
 @Column(length=128) private String action;
 @Column(length=128) private String operatorId;
 @Column(length=256) private String operatorName;
 @Column(length=8000) private String description;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class) private Map<String,Object> details;
}
