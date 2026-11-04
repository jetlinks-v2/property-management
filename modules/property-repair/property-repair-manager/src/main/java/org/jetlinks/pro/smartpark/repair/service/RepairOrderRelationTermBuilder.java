package org.jetlinks.pro.smartpark.repair.service;

import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.rdb.metadata.RDBColumnMetadata;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.PrepareSqlFragments;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.SqlFragments;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.term.AbstractTermFragmentBuilder;
import org.hswebframework.ezorm.rdb.utils.SqlUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 关联过程参与人和当前回访角色，复用工单查询/统计/导出的统一条件链。 */
@Configuration
public class RepairOrderRelationTermBuilder {
    public static final String PARTICIPANT = "repair-participant";
    public static final String CALLBACK = "repair-callback-role";
    public static final String CALLBACK_FALLBACK = "repair-callback-fallback";
    public static final String PENDING_REVIEW = "repair-pending-review";
    public static final String ARCHIVE_REVIEW = "repair-archive-review-role";

    @Bean public AbstractTermFragmentBuilder repairPendingReviewTerm() {
        return pendingReviewTerm(PENDING_REVIEW, "报事待审申请", false);
    }

    @Bean public AbstractTermFragmentBuilder repairArchiveReviewRoleTerm() {
        return pendingReviewTerm(ARCHIVE_REVIEW, "报事待审归档角色", true);
    }

    private AbstractTermFragmentBuilder pendingReviewTerm(String type,String name,boolean archiveOnly) {
        return new AbstractTermFragmentBuilder(type,name) {
            @Override public SqlFragments createFragments(String fullName,RDBColumnMetadata column,Term term) {
                var sql=PrepareSqlFragments.of();
                if(term.getOptions().contains("not"))sql.addSql("not");
                sql.addSql("exists(select 1 from",getTableName("sp_repair_review",column),
                    "rr where rr.order_id=",fullName,"and rr.status=1");
                if(archiveOnly)sql.addSql("and rr.kind='archive'");
                else if(term.getOptions().contains("abandon"))sql.addSql("and rr.kind='abandon'");
                // true用于判断是否存在阻塞动作的废弃审核；角色列表来自已认证的待办查询展开。
                if(!Boolean.TRUE.equals(term.getValue())) {
                    var roles=convertList(column,term);
                    sql.addSql("and rr.role_id in (").add(SqlUtils.createQuestionMarks(roles.size())).addSql(")").addParameter(roles);
                }
                return sql.addSql(")");
            }
        };
    }

    @Bean public AbstractTermFragmentBuilder repairCallbackFallbackTerm() {
        return new AbstractTermFragmentBuilder(CALLBACK_FALLBACK, "无回访角色时按责任人回访") {
            @Override public SqlFragments createFragments(String fullName,RDBColumnMetadata column,Term term) {
                // 与动作服务一致：流程缺失、停用或没有回访角色时回退当前责任人。
                return PrepareSqlFragments.of().addSql("exists(select 1 from",getTableName("sp_repair_order",column),
                        "ro left join",getTableName("sp_repair_configuration",column),
                        "rc on rc.id=ro.category_id and rc.application_id=ro.application_id and rc.business_project_id=ro.business_project_id",
                        "where ro.id=",fullName,"and ro.assigned_user_id=? and (rc.id is null",
                        "or cast(rc.configuration as jsonb)->>'flowConfigured'='false'",
                        "or cast(rc.configuration as jsonb)->>'flowEnabled'='false'",
                        "or coalesce(cast(rc.configuration as jsonb)->>'callbackRoleId','')=''))")
                    .addParameter(term.getValue());
            }
        };
    }

    @Bean public AbstractTermFragmentBuilder repairParticipantTerm() {
        return new AbstractTermFragmentBuilder(PARTICIPANT, "报事历史参与人") {
            @Override public SqlFragments createFragments(String fullName, RDBColumnMetadata column, Term term) {
                var users = convertList(column, term);
                var sql = PrepareSqlFragments.of();
                if (term.getOptions().contains("not")) sql.addSql("not");
                // 指派/转派的目标人员也属于历史责任人；人员ID取已保存的动作快照。
                return sql.addSql("exists(select 1 from", getTableName("sp_repair_process", column),
                        "rp where rp.order_id =", fullName, "and (rp.actor_id in (")
                    .add(SqlUtils.createQuestionMarks(users.size())).addSql(") or cast(rp.details as jsonb)->>'assignedUserId' in (")
                    .add(SqlUtils.createQuestionMarks(users.size())).addSql(")))")
                    .addParameter(users).addParameter(users);
            }
        };
    }

    @Bean public AbstractTermFragmentBuilder repairCallbackRoleTerm() {
        return new AbstractTermFragmentBuilder(CALLBACK, "报事当前回访角色") {
            @Override public SqlFragments createFragments(String fullName, RDBColumnMetadata column, Term term) {
                var roles = convertList(column, term);
                var sql = PrepareSqlFragments.of();
                if (term.getOptions().contains("not")) sql.addSql("not");
                // 精确匹配实际分类；停用/清空流程不产生角色候选，不向父分类继承。
                return sql.addSql("exists(select 1 from", getTableName("sp_repair_order", column),
                        "ro join", getTableName("sp_repair_configuration", column),
                        "rc on rc.id=ro.category_id and rc.application_id=ro.application_id and rc.business_project_id=ro.business_project_id",
                        "where ro.id=", fullName, "and rc.kind='category'",
                        "and coalesce(cast(rc.configuration as jsonb)->>'flowConfigured','true')<>'false'",
                        "and coalesce(cast(rc.configuration as jsonb)->>'flowEnabled','true')<>'false'",
                        "and cast(rc.configuration as jsonb)->>'callbackRoleId' in (")
                    .add(SqlUtils.createQuestionMarks(roles.size())).addSql("))").addParameter(roles);
            }
        };
    }
}
