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
/** 品质Configuration持久化模型；归属与状态转换由对应应用服务维护。 */
@Getter @Setter @EnableEntityEvent
@Asset(type="qualityConfiguration")
@Table(name="pq_configuration",indexes={@Index(name="idx_pq_config_scope",columnList="applicationId,kind,projectId,deleted"),@Index(name="uk_pq_config_key",columnList="applicationId,uniqueKey",unique=true)})
public class QualityConfigurationEntity extends QualityEntity {
 @Column(length=128) private String kind;
 @Column(length=256) private String name;
 @Column private Integer configLevel;
 /** 创建来源：公司菜单为1，项目菜单为2；创建后不可由客户端修改。 */
 @Column private Integer sourceType;
 /** 可检索维度均由已验证引用派生，不接受客户端直接指定列值。 */
 @Column(length=128) private String lineId;
 @Column(length=128) private String subjectId;
 @Column(length=128) private String categoryId;
 @Column private Integer inspectionFrequency;
 @Column(length=128) private String parentId;
 @Column private Integer status;
 @Column private Integer sortOrder;
 @Column(length=256) private String uniqueKey;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class) private Map<String,Object> data;
}
