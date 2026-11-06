package org.jetlinks.pro.property.decoration.service;

import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.rdb.metadata.RDBColumnMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBTableMetadata;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.BatchSqlFragments;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.SqlFragments;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.term.AbstractTermFragmentBuilder;
import org.hswebframework.web.crud.configuration.TableMetadataCustomizer;
import org.jetlinks.pro.property.decoration.entity.DecorationTaskEntity;
import org.springframework.stereotype.Component;
import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Set;

/** 当前角色匹配任务创建时的角色快照；只在装修任务表注册，参数绑定且分页前执行。 */
@Component
public class DecorationTaskRoleTermBuilder extends AbstractTermFragmentBuilder implements TableMetadataCustomizer {
    public static final String TYPE = "decoration-task-role";
    public DecorationTaskRoleTermBuilder() { super(TYPE, "装修任务角色"); }
    @Override
    public void customColumn(Class<?> type, PropertyDescriptor descriptor, Field field,
                             Set<Annotation> annotations, RDBColumnMetadata column) { }
    @Override
    public void customTable(Class<?> type, RDBTableMetadata table) {
        if (type == DecorationTaskEntity.class) table.getColumn("details").ifPresent(column -> column.addFeature(this));
    }
    @Override
    public SqlFragments createFragments(String name, RDBColumnMetadata column, Term term) {
        var ids = convertList(null, term);
        if (ids.isEmpty()) return new BatchSqlFragments().addSql("1=0");
        var sql = new BatchSqlFragments();
        boolean review = term.getOptions().contains("review");
        if (review) sql.addSql("(", name, "::jsonb->>'reviewRoleId' in (");
        else if (term.getOptions().contains("handled")) sql.addSql("exists (select 1 from jsonb_array_elements(coalesce(", name,
            "::jsonb->'handlingRecords','[]'::jsonb)) decoration_handling where decoration_handling->>'operatorId' in (");
        else sql.addSql("exists (select 1 from jsonb_array_elements(coalesce(", name,
            "::jsonb->'inspectionRoles','[]'::jsonb)) decoration_role where decoration_role->>'roleId' in (");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sql.addSql(",");
            sql.addSql("?").addParameter(ids.get(i).toString());
        }
        return sql.addSql("))");
    }
}
