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
/** 品质Draft持久化模型；归属与状态转换由对应应用服务维护。 */
@Getter @Setter @EnableEntityEvent
@Table(name="pq_draft",indexes={@Index(name="uk_pq_draft_user",columnList="workOrderId,executorId",unique=true)})
public class QualityDraftEntity extends QualityEntity {
 @Column(length=128) private String workOrderId;
 @Column(length=128) private String executorId;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class) private Map<String,Object> content;
}
