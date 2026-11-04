package org.jetlinks.pro.patrol.entity;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import java.sql.JDBCType;
import java.util.Map;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 等级和标签业务修改的不可变事实；读权限沿原配置，不产生第二套资产或通用写接口。 */
@Getter
@Setter
@Table(name = "pt_config_history", indexes = @Index(name = "pt_config_history_subject", columnList = "assetType,entityId,createTime"))
public class PatrolConfigHistoryEntity extends GenericEntity<String> {
    @Column(length = 64, nullable = false, updatable = false) private String assetType;
    @Column(length = 64, nullable = false, updatable = false) private String entityId;
    @Column(length = 160, nullable = false, updatable = false) private String scopeKey;
    @Column(length = 16, nullable = false, updatable = false) private String action;
    @Column(length = 64, nullable = false, updatable = false) private String actorId;
    @Column(length = 128, updatable = false) private String actorName;
    @Column(nullable = false, updatable = false) private Long createTime;
    @Column(updatable = false) @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    private Map<String, Object> beforeValue;
    @Column(updatable = false) @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    private Map<String, Object> afterValue;
}
