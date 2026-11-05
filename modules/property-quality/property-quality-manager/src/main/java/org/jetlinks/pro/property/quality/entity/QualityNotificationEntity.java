package org.jetlinks.pro.property.quality.entity;
import lombok.Getter;import lombok.Setter;import javax.persistence.*;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
/** 与待办事务提交的持久化通知队列；唯一事件键避免同版本重复入队。 */
@Getter @Setter @EnableEntityEvent @Table(name="pq_notification",indexes={@Index(name="idx_pq_notice_due",columnList="state,nextAttemptTime")})
public class QualityNotificationEntity extends QualityEntity {
 @Column(length=64)private String workOrderId;
 @Column(length=64)private String userId;
 @Column(length=32)private String action;
 @Column(length=32)private String state;
 @Column private Integer attempts;
 @Column private Long nextAttemptTime;
}
