package org.jetlinks.pro.property.base.user;

import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.rdb.metadata.RDBColumnMetadata;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.SqlFragments;
import org.jetlinks.pro.terms.SubTableTermFragmentBuilder;
import org.jetlinks.pro.utils.ConverterUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

/** id + property-user-org + {orgId$in:[...]}：数据库 exists，避免先全量拉取用户再分页。 */
@Component
public class UserOrganizationTerm extends SubTableTermFragmentBuilder {
    public UserOrganizationTerm() {
        super("property-user-org", "物业用户直接组织");
    }

    @Override
    protected String getSubTableName() {
        return "pm_user_org";
    }

    @Override
    protected String getSubTableColumn() {
        return "user_id";
    }

    @Override
    public SqlFragments createFragments(String column, RDBColumnMetadata metadata, Term input) {
        var terms = new ArrayList<>(ConverterUtils.convertTerms(input.getValue()));
        // 始终在外层追加有效关系约束，调用者的 OR 条件不能重新选中历史关系。
        var group = new Term();
        group.setTerms(terms);
        var valid = new Term();
        valid.setColumn("deleted");
        valid.setValue(false);
        var term = input.clone();
        term.setValue(java.util.List.of(group, valid));
        return super.createFragments(column, metadata, term);
    }
}
