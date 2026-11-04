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
/** 品质Export持久化模型；归属与状态转换由对应应用服务维护。 */
@Getter @Setter @EnableEntityEvent
@Table(name="pq_export",indexes={@Index(name="idx_pq_export_owner",columnList="applicationId,creatorId,createTime")})
public class QualityExportEntity extends QualityEntity {
 @Column(length=256) private String name;
 @Column(length=128) private String exportScope;
 @Column private Integer status;
 @Column(length=128) private String fileId;
 @Column(length=8000) private String failureReason;
 @Column private Integer workOrderCount;
 @Column private Integer recordCount;
 @Column private Integer imageCount;
 @Column private Long leaseUntil;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class) private List<String> sourceOrderIds;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class) private List<String> sourceTaskIds;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class) private Map<String,Object> query;
}
