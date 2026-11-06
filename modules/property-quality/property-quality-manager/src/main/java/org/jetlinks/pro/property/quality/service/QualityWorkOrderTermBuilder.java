package org.jetlinks.pro.property.quality.service;
import org.jetlinks.pro.terms.SubTableTermFragmentBuilder;
import org.springframework.stereotype.Component;
/** 子记录查询反向关联已注入资产范围的工单条件，分页及导出均复用此边界。 */
@Component public class QualityWorkOrderTermBuilder extends SubTableTermFragmentBuilder {
 public static final String TYPE="quality-work-order";
 public QualityWorkOrderTermBuilder(){super(TYPE,"品质工单范围");}
 protected String getSubTableColumn(){return "id";}
 protected String getSubTableName(){return "pq_work_order";}
}
