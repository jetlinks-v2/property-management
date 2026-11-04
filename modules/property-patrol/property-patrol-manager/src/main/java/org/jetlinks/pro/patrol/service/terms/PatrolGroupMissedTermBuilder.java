package org.jetlinks.pro.patrol.service.terms;

import org.jetlinks.pro.terms.SubTableTermFragmentBuilder;
import org.springframework.stereotype.Component;

/** 按真实漏检次数筛选日任务组，存在性判断在分页前执行，避免一组多次申请重复行。 */
@Component
public class PatrolGroupMissedTermBuilder extends SubTableTermFragmentBuilder {
    public static final String TERM_TYPE = "patrol-group-missed";

    public PatrolGroupMissedTermBuilder() {
        super(TERM_TYPE, "巡更日任务漏检申报");
    }

    @Override
    protected String getSubTableColumn() {
        return "taskGroupId";
    }

    @Override
    protected String getSubTableName() {
        return "pt_missed_item";
    }
}
