package org.jetlinks.pro.property.facility.entity;

import javax.persistence.Table;
import javax.persistence.Index;

/** 每轮异常独立快照；持续异常更新同轮，恢复再触发产生新轮次，不覆盖历史。 */
@Table(name = "fe_alert_episode", indexes = @Index(name = "idx_fe_episode_scope", columnList = "tenantId,applicationId,projectId,firstTime"))
public class AlertEpisodeEntity extends AlertEntity {}
