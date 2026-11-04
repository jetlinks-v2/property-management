package org.jetlinks.pro.patrol.entity;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 申报、审核、撤回的不可变事实；重复操作不得覆盖原操作人、意见或时间。 */
@Getter
@Setter
@Table(name = "pt_missed_event", indexes = @Index(name = "pt_missed_event_item", columnList = "itemId,createTime"))
public class PatrolMissedEventEntity extends GenericEntity<String> {
    @Column(length = 64, nullable = false, updatable = false) private String itemId;
    @Column(length = 64, nullable = false, updatable = false) private String applicationId;
    @Column(length = 16, nullable = false, updatable = false) private String action;
    @Column(length = 64, nullable = false, updatable = false) private String actorId;
    @Column(length = 128, updatable = false) private String actorName;
    @Column(length = 500, updatable = false) private String remark;
    @Column(nullable = false, updatable = false) private Long createTime;
}
