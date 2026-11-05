package org.jetlinks.pro.property.quality.service;
import org.jetlinks.pro.terms.SubTableTermFragmentBuilder;import org.springframework.stereotype.Component;
/** 使用平台 EXISTS 子表查询个人待办，避免将全量个人工单 ID 收集到内存或展开 IN。 */
@Component public class QualityParticipantTermBuilder extends SubTableTermFragmentBuilder {
 public static final String TERM_TYPE="quality-participant";
 public QualityParticipantTermBuilder(){super(TERM_TYPE,"品质参与人");}
 protected String getSubTableColumn(){return "workOrderId";}
 protected String getSubTableName(){return "pq_participant";}
}
