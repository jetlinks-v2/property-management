package org.jetlinks.pro.property.decoration.service;

import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.rdb.metadata.RDBColumnMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBTableMetadata;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.BatchSqlFragments;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.SqlFragments;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.term.AbstractTermFragmentBuilder;
import org.hswebframework.web.crud.configuration.TableMetadataCustomizer;
import org.jetlinks.pro.property.decoration.entity.DecorationApplicationEntity;
import org.springframework.stereotype.Component;
import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

/** 概况钻取复用申请查询及其权限注入、分页；查询时间沿用报告时刻。 */
@Component
public class DecorationOverviewMetricTermBuilder extends AbstractTermFragmentBuilder implements TableMetadataCustomizer {
    public static final String TYPE = "decoration-overview-metric";
    public DecorationOverviewMetricTermBuilder() { super(TYPE, "装修概况指标"); }
    @Override public void customColumn(Class<?> type, PropertyDescriptor descriptor, Field field, Set<Annotation> annotations, RDBColumnMetadata column) {}
    @Override public void customTable(Class<?> type, RDBTableMetadata table) {
        if (type == DecorationApplicationEntity.class) table.getColumn("id").ifPresent(column -> column.addFeature(this));
    }
    @Override public SqlFragments createFragments(String name, RDBColumnMetadata column, Term term) {
        if (!(term.getValue() instanceof Map<?, ?> value) || !(value.get("asOf") instanceof Number time))
            throw DecorationRules.error("invalid_overview_metric", 400);
        DecorationOverviewMetric metric;
        try { metric = DecorationOverviewMetric.valueOf(String.valueOf(value.get("metric"))); }
        catch (IllegalArgumentException e) { throw DecorationRules.error("invalid_overview_metric", 400); }
        var condition = metric.condition(time.longValue());
        return new BatchSqlFragments().addSql(name, "in (select a.id from pd_application a where a.deleted=false and (", condition.sql(), "))")
            .addParameter(condition.parameters().toArray());
    }
}
