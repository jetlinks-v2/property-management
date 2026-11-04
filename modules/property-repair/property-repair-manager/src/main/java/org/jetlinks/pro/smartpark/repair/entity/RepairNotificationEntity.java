package org.jetlinks.pro.smartpark.repair.entity;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import javax.persistence.Column;
import javax.persistence.Table;
import javax.persistence.Index;
import java.sql.JDBCType;
import java.util.Map;

/** 每个过程、收件人一条事务内发件记录；不作为对外查询接口或资产。 */
@Getter @Setter
@Table(name="sp_repair_notification",indexes=@Index(name="idx_repair_notify_due",columnList="state,nextAttemptTime"))
public class RepairNotificationEntity extends GenericEntity<String> {
    @Column(length=64,nullable=false) private String processId;
    @Column(length=64,nullable=false) private String orderId;
    @Column(length=64,nullable=false) private String tenantId;
    @Column(length=64,nullable=false) private String applicationId;
    @Column(length=64,nullable=false) private String userId;
    @Column(length=64) private String roleId;
    @Column(length=32) private String requiredAction;
    @Column(length=32) private String action;
    @Column(length=500) private String message;
    @Column(length=16,nullable=false) private String state;
    @Column private Long createTime;
    @Column private Long nextAttemptTime;
    @Column private Long sentTime;
    @Column private Integer attempts;
    @Column(length=128) private String lastErrorType;
    @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class)
    private Map<String,Object> detail;
}
