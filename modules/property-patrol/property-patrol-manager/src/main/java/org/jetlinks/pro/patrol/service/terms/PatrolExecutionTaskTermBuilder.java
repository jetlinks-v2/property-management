package org.jetlinks.pro.patrol.service.terms;

import org.jetlinks.pro.terms.SubTableTermFragmentBuilder;
import org.springframework.stereotype.Component;

/** 执行事实关联已授权的任务筛选，避免月报逐批传输任务 ID 并往返查询执行表。 */
@Component
public class PatrolExecutionTaskTermBuilder extends SubTableTermFragmentBuilder {
    public static final String TERM_TYPE = "patrol-execution-task";
    public PatrolExecutionTaskTermBuilder() { super(TERM_TYPE, "巡更执行所属任务"); }
    // 与计划权限子查询使用不同别名，避免嵌套时遮蔽任务关联列。
    @Override protected String getTableAlias() { return "stat_task_"; }
    @Override protected String getSubTableColumn() { return "id"; }
    @Override protected String getSubTableName() { return "pt_task"; }
}
