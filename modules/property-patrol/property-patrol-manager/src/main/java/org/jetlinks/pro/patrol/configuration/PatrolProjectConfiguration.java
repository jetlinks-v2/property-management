package org.jetlinks.pro.patrol.configuration;

import org.jetlinks.pro.terms.SubTableTermFragmentBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 关联 id/planId 的查询均在数据库分页前过滤项目，不在内存裁剪分页结果。 */
@Configuration
public class PatrolProjectConfiguration {
    private SubTableTermFragmentBuilder term(String type, String table) {
        return new SubTableTermFragmentBuilder("patrol-project-" + type, "巡更项目归属") {
            @Override protected String getSubTableColumn() { return "id"; }
            @Override protected String getSubTableName() { return table; }
        };
    }
    @Bean public SubTableTermFragmentBuilder patrolProjectLevelTerm() { return term("patrolLevel", "pt_level"); }
    @Bean public SubTableTermFragmentBuilder patrolProjectTagTerm() { return term("patrolTag", "pt_tag"); }
    @Bean public SubTableTermFragmentBuilder patrolProjectWarningTerm() { return term("patrolWarning", "pt_warning"); }
    @Bean public SubTableTermFragmentBuilder patrolProjectCategoryTerm() { return term("patrolCategory", "pt_category"); }
    @Bean public SubTableTermFragmentBuilder patrolProjectPointTerm() { return term("patrolPoint", "pt_point"); }
    @Bean public SubTableTermFragmentBuilder patrolProjectStandardTerm() { return term("patrolStandard", "pt_standard"); }
    @Bean public SubTableTermFragmentBuilder patrolProjectAttachmentTerm() { return term("patrolAttachment", "pt_attachment"); }
    @Bean public SubTableTermFragmentBuilder patrolProjectRosterTerm() { return term("patrolRoster", "pt_roster"); }
    @Bean public SubTableTermFragmentBuilder patrolProjectPlanTerm() { return term("patrolPlan", "pt_plan"); }
}
