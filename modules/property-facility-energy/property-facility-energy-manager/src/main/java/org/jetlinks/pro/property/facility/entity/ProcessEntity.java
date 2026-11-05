package org.jetlinks.pro.property.facility.entity;

import java.math.BigDecimal;
import java.sql.JDBCType;
import java.util.*;
import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.*;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.jetlinks.pro.assets.annotation.Asset;
import org.jetlinks.pro.property.facility.model.*;

/** Process持久化记录；来源、权限、版本及生命周期由对应领域服务维护。 */
@Getter
@Setter
@EnableEntityEvent
@Table(
  name = "fe_process",
  indexes = @Index(
    name = "idx_fe_process_scope",
    columnList = "tenantId,applicationId,targetId,createTime",
    unique = false
  )
)
public class ProcessEntity extends FacilityEntity {

  @Column(length = 256)
  private String targetType;

  @Column(length = 256)
  private String targetId;

  @Column(length = 256)
  private String action;

  @Column(length = 256)
  private String fromState;

  @Column(length = 256)
  private String toState;

  @Column(length = 4000)
  private String comment;

  @Column
  @JsonCodec
  @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
  private List<String> attachments = new ArrayList<>();
}
