package org.jetlinks.pro.property.decoration.entity;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 装修自有通知及持久化发件箱；不暴露通用写入或任意收件人接口。 */
@Getter @Setter
@Table(name="pd_notification", indexes={
    @Index(name="idx_pd_notice_recipient",columnList="tenantId,applicationId,userId,createTime"),
    @Index(name="idx_pd_notice_delivery",columnList="state,nextAttemptTime")})
public class DecorationNotificationEntity extends GenericEntity<String> {
    @Column(length=64,nullable=false,updatable=false) private String decorationId;
    @Column(length=64,nullable=false,updatable=false) private String tenantId;
    @Column(length=64,nullable=false,updatable=false) private String applicationId;
    @Column(length=64,nullable=false,updatable=false) private String projectId;
    @Column(length=64,nullable=false,updatable=false) private String userId;
    /** 既有记录为空时按业主通知读取；新记录明确区分端别和任务，避免双身份串信。 */
    @Column(length=16,updatable=false) private String recipientSide;
    @Column(length=64,updatable=false) private String taskId;
    @Column(updatable=false) private Long taskVersion;
    @Column(length=64,updatable=false) private String roleId;
    @Column(length=64,updatable=false) private String dispatcherId;
    @Column(length=64,updatable=false) private String event;
    @Column(length=32,updatable=false) private String step;
    @Column(length=128,updatable=false) private String title;
    @Column(length=1000,updatable=false) private String message;
    @Column(length=64,updatable=false) private String number;
    @Column(length=512,updatable=false) private String spaceName;
    @Column(length=1000,updatable=false) private String detailUrl;
    @Column(updatable=false) private Long createTime;
    @Column(length=16,nullable=false) private String state;
    @Column private Integer attempts;
    @Column private Long nextAttemptTime;
    @Column private Long sentTime;
    @Column(length=128) private String lastErrorType;
}
