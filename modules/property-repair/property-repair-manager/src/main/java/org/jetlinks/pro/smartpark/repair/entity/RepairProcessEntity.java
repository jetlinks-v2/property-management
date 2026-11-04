package org.jetlinks.pro.smartpark.repair.entity;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.Column;
import javax.persistence.Table;
import javax.persistence.Index;
import org.hswebframework.web.api.crud.entity.ExtendableEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.jetlinks.pro.assets.annotation.Asset;
import java.sql.JDBCType;
import java.util.List;
import java.util.Map;
/** 工单动作的不可变过程记录；与工单状态变更处于同一事务。 */
@Getter @Setter @EnableEntityEvent
@Table(name="sp_repair_process",indexes=@Index(name="idx_repair_process_order",columnList="orderId,createTime"))
public class RepairProcessEntity extends ExtendableEntity<String> {
 @Column(length=64,nullable=false) private String orderId;
 @Column(length=64) private String tenantId;
 @Column(length=64) private String applicationId;
 @Column(length=32) private String action;
 @Column(length=64) private String requestId;
 @Column(length=64) private String actorId;
 @Column(length=128) private String actorName;
 @Column private Integer beforeStatus;
 @Column private Integer afterStatus;
 @Column private Long orderVersion;
 @Column(length=4000) private String description;
 @Column private Long createTime;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class)
 private List<String> attachmentIds;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class)
 private Map<String,Object> details;
}
