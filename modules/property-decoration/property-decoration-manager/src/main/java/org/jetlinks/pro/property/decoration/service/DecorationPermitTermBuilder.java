package org.jetlinks.pro.property.decoration.service;

import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.rdb.metadata.RDBColumnMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBTableMetadata;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.BatchSqlFragments;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.SqlFragments;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.term.AbstractTermFragmentBuilder;
import org.hswebframework.ezorm.rdb.supports.json.JsonValueCondition;
import org.hswebframework.ezorm.rdb.supports.postgres.PostgresqlJsonTermFragmentBuilder;
import org.hswebframework.web.crud.configuration.TableMetadataCustomizer;
import org.jetlinks.pro.property.decoration.entity.DecorationApplicationEntity;
import org.springframework.stereotype.Component;

import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Set;

import static org.jetlinks.pro.property.decoration.service.DecorationRules.require;

/**
 * 备案状态按服务端查询时刻计算，过期优先于延期；使用框架 JSON 条件保留数据库分页。
 * 装修独立服务已限定 PostgreSQL；仅为自身 permit 文本快照注册查询特性，不改列类型或公共元数据。
 */
@Component
public class DecorationPermitTermBuilder extends AbstractTermFragmentBuilder implements TableMetadataCustomizer {
    public static final String TYPE = "decoration-permit-state";
    private static final Set<String> STATES = Set.of("pending", "issued", "extended", "expired");

    public DecorationPermitTermBuilder() {
        super(TYPE, "装修备案状态");
    }

    @Override
    public void customColumn(Class<?> entityType, PropertyDescriptor descriptor, Field field,
                             Set<Annotation> annotations, RDBColumnMetadata column) {
    }

    @Override
    public void customTable(Class<?> entityType, RDBTableMetadata table) {
        if (entityType == DecorationApplicationEntity.class) {
            table.getColumn("permit").ifPresent(column -> {
                column.addFeature(PostgresqlJsonTermFragmentBuilder.value);
                column.addFeature(this);
            });
        }
    }

    @Override
    public SqlFragments createFragments(String columnName, RDBColumnMetadata column, Term term) {
        var states = convertList(null, term);
        require(!states.isEmpty() && states.stream().allMatch(STATES::contains), "invalid_permit_state");
        long now = System.currentTimeMillis();
        var result = new BatchSqlFragments();
        if (term.getOptions().contains("not")) result.addSql("not");
        result.addSql("(");
        for (int i = 0; i < states.size(); i++) {
            if (i > 0) result.addSql("or");
            result.addFragments(state(columnName, column, states.get(i).toString(), now));
        }
        return result.addSql(")");
    }

    private SqlFragments state(String name, RDBColumnMetadata column, String state, long now) {
        // 与流程门槛一致：当前版本打印或下载即完成备案，兼容既有领取信息。
        var printed = combine("or", json(name, column, "collectedTime", "notnull", 1),
            json(name, column, "printTime", "notnull", 1), json(name, column, "downloadTime", "notnull", 1));
        if ("pending".equals(state)) return new BatchSqlFragments().addSql("not (").addFragments(printed).addSql(")");
        var end = json(name, column, "endTime", "lt", now);
        if ("expired".equals(state)) return combine("and", printed, end);
        var valid = combine("or", json(name, column, "endTime", "isnull", 1), json(name, column, "endTime", "gte", now));
        var extension = json(name, column, "extensions[0]", "extended".equals(state) ? "notnull" : "isnull", 1);
        return combine("and", printed, valid, extension);
    }

    private SqlFragments json(String name, RDBColumnMetadata column, String path, String operator, Object value) {
        var term = new Term();
        term.setValue(JsonValueCondition.of(path, operator, "isnull".equals(operator) || "notnull".equals(operator) ? null : value));
        return PostgresqlJsonTermFragmentBuilder.value.createFragments(name, column, term);
    }

    private SqlFragments combine(String operator, SqlFragments... children) {
        var result = new BatchSqlFragments().addSql("(");
        for (int i = 0; i < children.length; i++) {
            if (i > 0) result.addSql(operator);
            result.addFragments(children[i]);
        }
        return result.addSql(")");
    }
}
