package org.jetlinks.pro.patrol.service.terms;

import org.jetlinks.pro.terms.SubTableTermFragmentBuilder;
import org.springframework.stereotype.Component;

/** 点位按已指派岗位筛选：id$patrol-point-plan，value 使用标准子条件 {postId$in:[...]}。 */
@Component
public class PatrolPointPlanTermBuilder extends SubTableTermFragmentBuilder {
    public static final String TERM_TYPE = "patrol-point-plan";
    public PatrolPointPlanTermBuilder() { super(TERM_TYPE, "巡更点位规则指派"); }
    @Override protected String getSubTableColumn() { return "pointId"; }
    @Override protected String getSubTableName() { return "pt_plan"; }
}
