package org.jetlinks.pro.patrol.service.terms;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.rdb.metadata.RDBColumnMetadata;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.PrepareSqlFragments;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.SqlFragments;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.term.AbstractTermFragmentBuilder;
import org.springframework.stereotype.Component;

/** JSON 文本标签数组按完整字符串成员匹配，使用标准 LIKE/ESCAPE，避免 PostgreSQL 专用 JSON 运算。 */
@Component
public class PatrolPointTagsTermBuilder extends AbstractTermFragmentBuilder {
    public static final String TERM_TYPE = "patrol-point-tags";
    private final ObjectMapper mapper;
    public PatrolPointTagsTermBuilder(ObjectMapper mapper) {
        super(TERM_TYPE, "巡更点位标签");
        this.mapper = mapper;
    }
    @Override
    public SqlFragments createFragments(String columnFullName, RDBColumnMetadata column, Term term) {
        var ids = convertList(null, term);
        var fragments = PrepareSqlFragments.of();
        if (term.getOptions().contains("not")) fragments.addSql("not");
        fragments.addSql("(");
        if (ids.isEmpty()) fragments.addSql("1=0");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) fragments.addSql("or");
            // 完整 JSON 字符串含双引号边界；转义通配符与转义字符，不能把标签 ID 当搜索表达式。
            String json;
            try { json = mapper.writeValueAsString(String.valueOf(ids.get(i))); }
            catch (JsonProcessingException error) { throw new IllegalArgumentException(error); }
            String literal = json.replace("!", "!!").replace("%", "!%").replace("_", "!_");
            fragments.addSql("coalesce(", columnFullName, ",'[]') like ? escape '!'")
                    .addParameter("%" + literal + "%");
        }
        return fragments.addSql(")");
    }
}
