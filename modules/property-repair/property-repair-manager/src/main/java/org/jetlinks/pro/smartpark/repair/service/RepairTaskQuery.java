package org.jetlinks.pro.smartpark.repair.service;

import lombok.AllArgsConstructor;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.service.PropertyPersonService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/** 员工任务池按所选状态解释，不与登记/历史参与的“与我相关”混合。 */
@Component @AllArgsConstructor
public class RepairTaskQuery {
    public static final String TERM_TYPE = "repair-task";
    public static final String TODO_TERM_TYPE = "repair-todo";
    private static final List<Integer> STATUSES = List.of(0,1,2,3,4,5,6,7,8,9);
    private final PropertyPersonService people;

    public Mono<QueryParamEntity> resolve(QueryParamEntity source, RepairContext context) {
        QueryParamEntity query = source.clone();
        if (!contains(query.getTerms())) return Mono.just(query);
        return Mono.zip(Authentication.currentReactive(), AssetsHolder.currentReactive(),
                people.findById(context.userId()).map(person -> context.tenantId().equals(person.getTenantId())
                    && Boolean.TRUE.equals(person.getEmployee()) && person.getSourceStatus()!=null && person.getSourceStatus()==1)
                    .defaultIfEmpty(false))
            .flatMap(access -> access.getT2().injectQuery(new QueryParamEntity(), "repairOrder", "id", CrudAssetPermission.save)
                .map(save -> {
                    expand(query.getTerms(),context.userId(),access.getT1(),access.getT3(),save.getTerms());
                    return query;
                }));
    }

    private static boolean contains(List<Term> terms) {
        return terms.stream().anyMatch(term -> (TERM_TYPE.equals(term.getTermType()) || TODO_TERM_TYPE.equals(term.getTermType())) || contains(term.getTerms()));
    }

    private static void expand(List<Term> terms,String user,Authentication auth,boolean employee,List<Term> save) {
        for (Term term : terms) {
            if (!TERM_TYPE.equals(term.getTermType()) && !TODO_TERM_TYPE.equals(term.getTermType())) {expand(term.getTerms(),user,auth,employee,save);continue;}
            RepairFlowRules.require("id".equals(term.getColumn()) && term.getValue() instanceof Number, "task_query_invalid");
            Number value=(Number)term.getValue();
            int status=value.intValue();
            RepairFlowRules.require(value.doubleValue()==status && (status==-1 || STATUSES.contains(status)), "task_query_invalid");
            boolean todo=TODO_TERM_TYPE.equals(term.getTermType());
            RepairFlowRules.require(!todo || List.of(-1,6,7,1,8,9).contains(status), "task_query_invalid");
            List<Term> selected=todo?todoSelection(status,user,auth,employee,save):selection(status,user,auth,employee,save);
            term.setColumn(null);term.setValue(null);term.setTermType("eq");term.setTerms(selected);
        }
    }

    /** 全部/待分派只受外层读取权限限制；具体任务状态不因客户端指定身份扩大候选池。 */
    static List<Term> selection(int status,String user,Authentication auth,boolean employee,List<Term> save) {
        var terms=new ArrayList<Term>();
        terms.add(leaf("status",status==-1?"in":"eq",status==-1?STATUSES:status));
        if (status==-1 || status==0) return terms;
        List<String> roles=auth.getDimensions("role").stream().map(role -> role.getId()).toList();
        if (status==6) {
            if (!employee || !List.of("accept","follow","finish").stream().allMatch(action -> auth.hasPermission("repair-order",action))) {
                terms.add(leaf("id","eq",""));return terms;
            }
            var candidates=new ArrayList<Term>();candidates.add(leaf("assignedUserId","eq",user));
            if (!roles.isEmpty()) {
                Term pool=group(List.of(leaf("assignedUserId","isnull",true),leaf("candidateRoleId","in",roles)));
                pool.setType(Term.Type.or);candidates.add(pool);
            }
            terms.add(group(candidates));terms.add(group(save));
        } else if (status==8 || status==9) {
            String action=status==8?"callback":"review";
            if (!employee || !auth.hasPermission("repair-order",action) || (status==9&&roles.isEmpty())) {
                terms.add(leaf("id","eq",""));return terms;
            }
            if (status==8) {
                var callback=new ArrayList<Term>();
                callback.add(leaf("id",RepairOrderRelationTermBuilder.CALLBACK_FALLBACK,user));
                if (!roles.isEmpty()) {Term role=leaf("id",RepairOrderRelationTermBuilder.CALLBACK,roles);role.setType(Term.Type.or);callback.add(role);}
                terms.add(group(callback));
            } else terms.add(leaf("id",RepairOrderRelationTermBuilder.ARCHIVE_REVIEW,roles));
            terms.add(group(save));
        } else terms.add(leaf("assignedUserId","eq",user));
        return terms;
    }


    /** 我的待办仅包含当前可执行的接单、跟进、回访和各类审核；不混入已办历史或待分派池。 */
    static List<Term> todoSelection(int status,String user,Authentication auth,boolean employee,List<Term> save) {
        var branches=new ArrayList<Term>();
        if (!employee) return List.of(leaf("id","eq",""));
        List<String> roles=auth.getDimensions("role").stream().map(role->role.getId()).toList();
        // 待办统一与保存范围相交，外层仍保留工单读取、租户与应用范围。
        if ((status==-1||status==6) && List.of("accept","follow","finish").stream().allMatch(a->auth.hasPermission("repair-order",a))) {
            var eligible=new ArrayList<Term>(); eligible.add(leaf("assignedUserId","eq",user));
            if (!roles.isEmpty()) {Term pool=group(List.of(leaf("assignedUserId","isnull",true),leaf("candidateRoleId","in",roles)));pool.setType(Term.Type.or);eligible.add(pool);}
            branches.add(group(List.of(leaf("status","eq",6),group(eligible),withoutAbandonReview())));
        }
        if ((status==-1||status==7||status==1) && auth.hasPermission("repair-order","follow")) {
            branches.add(group(List.of(leaf("status",status==-1?"in":"eq",status==-1?List.of(7,1):status),leaf("assignedUserId","eq",user),withoutAbandonReview())));
        }
        if ((status==-1||status==8) && auth.hasPermission("repair-order","callback")) {
            var eligible=new ArrayList<Term>();eligible.add(leaf("id",RepairOrderRelationTermBuilder.CALLBACK_FALLBACK,user));
            if (!roles.isEmpty()) {Term role=leaf("id",RepairOrderRelationTermBuilder.CALLBACK,roles);role.setType(Term.Type.or);eligible.add(role);}
            branches.add(group(List.of(leaf("status","eq",8),group(eligible),withoutAbandonReview())));
        }
        if ((status==-1||status==9) && auth.hasPermission("repair-order","review") && !roles.isEmpty()) {
            // 审核由待审申请的角色决定，延期/废弃等申请不会把工单主状态改成9。
            branches.add(leaf("id",RepairOrderRelationTermBuilder.PENDING_REVIEW,roles));
        }
        if (branches.isEmpty()) return List.of(leaf("id","eq",""));
        for(int i=1;i<branches.size();i++)branches.get(i).setType(Term.Type.or);
        return List.of(group(save),group(branches));
    }

    private static Term withoutAbandonReview() {
        Term term=leaf("id",RepairOrderRelationTermBuilder.PENDING_REVIEW,true);
        term.setOptions(List.of("not","abandon"));return term;
    }

    private static Term leaf(String column,String type,Object value) {
        Term term=new Term();term.setColumn(column);term.setTermType(type);term.setValue(value);return term;
    }
    private static Term group(List<Term> terms) {Term term=new Term();term.setTerms(terms);return term;}
}
