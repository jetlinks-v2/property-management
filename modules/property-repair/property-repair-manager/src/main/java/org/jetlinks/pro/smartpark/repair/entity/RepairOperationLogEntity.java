package org.jetlinks.pro.smartpark.repair.entity;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 应用内接口审计投影；不保存认证头、请求正文和异常堆栈。 */
@Getter @Setter
@Table(name="sp_repair_operation_log", indexes=@Index(name="idx_repair_log_scope_time",columnList="tenantId,applicationId,createTime"))
public class RepairOperationLogEntity extends GenericEntity<String> {
    @Column(length=64,nullable=false,updatable=false) private String tenantId;
    @Column(length=64,nullable=false,updatable=false) private String applicationId;
    @Column(length=128) private String operatorId;
    @Column(length=128) private String operatorName;
    @Column(length=512) private String operation;
    @Column(length=128) private String action;
    @Column(length=16) private String httpMethod;
    @Column(length=1024) private String url;
    @Column private Integer status;
    @Column(length=128) private String errorType;
    @Column private Long duration;
    @Column private Long createTime;
}
