package org.jetlinks.pro.patrol.service.terms;

import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.rdb.metadata.RDBColumnMetadata;
import org.hswebframework.ezorm.rdb.metadata.dialect.Dialect;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.PrepareSqlFragments;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.SqlFragments;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.term.AbstractTermFragmentBuilder;
import org.springframework.stereotype.Component;

/** 已授权统计时段的请求内快照；单个数组参数避免海量 IN 占位符和重复扫描任务、计划授权。 */
@Component
public class PatrolStatisticsTaskIdsTermBuilder extends AbstractTermFragmentBuilder {
    public static final String TERM_TYPE = "patrol-statistics-task-ids";

    public PatrolStatisticsTaskIdsTermBuilder() {
        super(TERM_TYPE, "巡更统计已授权时段");
    }

    @Override
    public SqlFragments createFragments(String columnFullName, RDBColumnMetadata column, Term term) {
        var ids = convertList(null, term).stream().map(String::valueOf).distinct().toArray(String[]::new);
        if (ids.length == 0) return PrepareSqlFragments.of("1=0");
        // 标量子查询只求值一次，避免 PostgreSQL 对大数组展开估算后退回执行主表全扫。
        // H2 对标量子查询的 ANY 解析不同，测试库使用其原生数组绑定。
        String predicate = column != null && Dialect.H2.getId().equals(column.getDialect().getId())
                ? "= any(?)" : "= any(cast((select ?) as varchar array))";
        return PrepareSqlFragments.of().addSql(columnFullName, predicate)
                .addParameter((Object) ids);
    }
}
