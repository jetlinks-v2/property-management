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
/** 品质Rectification持久化模型；归属与状态转换由对应应用服务维护。 */
@Getter @Setter @EnableEntityEvent
@Table(name="pq_rectification",indexes={@Index(name="idx_pq_rect_order",columnList="workOrderId,rectificationStatus")})
public class QualityRectificationEntity extends QualityEntity {
 @Column(length=128) private String workOrderId;
 @Column(length=128) private String taskId;
 @Column(length=256) private String taskName;
 @Column private Integer taskLevel;
 @Column private Integer rectificationStatus;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class) private List<String> recordIds;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class) private List<String> executorIds;
 @Column(length=128) private String submitterId;
 @Column(length=256) private String submitterName;
 @Column(length=8000) private String rectificationDesc;
 @Column private Long rectificationTime;
 @Column(length=128) private String reviewerId;
 @Column(length=256) private String reviewerName;
 @Column private Long reviewTime;
 @Column(length=8000) private String rejectReason;
}
