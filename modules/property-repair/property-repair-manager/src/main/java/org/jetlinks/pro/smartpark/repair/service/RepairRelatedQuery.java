package org.jetlinks.pro.smartpark.repair.service;

import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.List;

/** 个人关系记录：本人登记、当前/历史责任人及过程参与人；候选待办由repair-todo单独查询。 */
@Component
public class RepairRelatedQuery {
    public static final String TERM_TYPE = "repair-related";

    public Mono<QueryParamEntity> resolve(QueryParamEntity source, RepairContext context) {
        QueryParamEntity query = source.clone();
        expand(query.getTerms(), context.userId());
        return Mono.just(query);
    }

    // 关系只收窄结果，组织、应用和工单读取权限仍由控制器统一注入；历史参与不授予操作权。
    private static void expand(List<Term> terms, String user) {
        for (Term term : terms) {
            if (TERM_TYPE.equals(term.getTermType())) {
                RepairFlowRules.require("id".equals(term.getColumn()) && Boolean.TRUE.equals(term.getValue()), "related_query_invalid");
                Term assigned = Term.of("assignedUserId", "eq", user); assigned.setType(Term.Type.or);
                Term participant = Term.of("id", RepairOrderRelationTermBuilder.PARTICIPANT, user); participant.setType(Term.Type.or);
                term.setColumn(null); term.setValue(null); term.setTermType("eq");
                term.setTerms(List.of(Term.of("creatorId", "eq", user), assigned, participant));
            } else expand(term.getTerms(), user);
        }
    }
}
