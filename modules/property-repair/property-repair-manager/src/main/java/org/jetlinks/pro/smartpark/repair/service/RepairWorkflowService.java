package org.jetlinks.pro.smartpark.repair.service;

import lombok.AllArgsConstructor;
import org.hswebframework.web.exception.NotFoundException;
import org.hswebframework.web.id.IDGenerator;
import org.jetlinks.pro.smartpark.repair.api.RepairActionRequest;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairReviewEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;

/** 审核规则与快照；所有写操作在调用者工单事务内完成，工单版本负责串行化。 */
@Service @AllArgsConstructor
public class RepairWorkflowService {
    private final RepairReviewService reviews;
    private final RepairRoleService roles;
    private final RepairConfigurationManager configurations;

    public Mono<Void> guard(RepairOrderEntity order,String action){
        if("review".equals(action))return Mono.empty();
        var pending=reviews.createQuery().where(RepairReviewEntity::getOrderId,order.getId()).and(RepairReviewEntity::getStatus,1);
        if("changeLevel".equals(action))pending.in(RepairReviewEntity::getKind,java.util.List.of("abandon","levelChange"));
        else if(!Set.of("finish","transfer","close","changeCategory","abandonApply").contains(action))pending.and(RepairReviewEntity::getKind,"abandon");
        return pending.count().map(count->count>0).doOnNext(exists->RepairFlowRules.require(!exists,"review_pending")).then();
    }

    public Mono<Integer> prepare(RepairOrderEntity order,RepairContext context,String action,RepairActionRequest input,
                                 Map<String,Object> flow,int next){
        validateForm(action,input);
        return switch(action){
            case "delay" -> applyDelay(order,context,input,flow).thenReturn(next);
            case "close" -> {
                // 旧版的非关申请原因就是完成表单的“完成情况”，不另造一个必填原因字段。
                input.setReason(input.getDescription());
                yield submit(order,context,input,"close",text(flow,"closeRoleId"),null).thenReturn(next);
            }
            case "abandonApply" -> submit(order,context,input,"abandon",text(flow,"abandonRoleId"),null).thenReturn(next);
            case "changeLevel" -> changeLevel(order,context,input,flow).thenReturn(next);
            case "review" -> decide(order,context,input);
            case "finish", "callback" -> next==9?submit(order,context,input,"archive",order.getLevelAuditRoleId(),snapshot(order)).thenReturn(next):Mono.just(next);
            default -> Mono.just(next);
        };
    }
    static void validateForm(String action,RepairActionRequest input){
        if(Set.of("follow","finish","quote","close","callback","review").contains(action)){
            RepairFlowRules.require(input.getDescription()!=null&&!input.getDescription().isBlank(),"description_required");
        }
        int descriptionLimit=switch(action){
            case "follow","finish","quote","close","review" -> 800;
            case "callback" -> 300;
            default -> 200;
        };
        RepairFlowRules.require(input.getDescription()==null||input.getDescription().length()<=descriptionLimit,"action_content_too_long");
        int reasonLimit="delay".equals(action)?800:"review".equals(action)?800:200;
        RepairFlowRules.require(input.getReason()==null||input.getReason().length()<=reasonLimit,"action_reason_too_long");
        if("callback".equals(action)){
            RepairFlowRules.require(Set.of("电话","登门","现场","短信").contains(Objects.toString(input.getCallbackMethod(),"")),"callback_form_required");
            RepairFlowRules.require(Set.of("有效","无效").contains(Objects.toString(input.getCallbackResult(),"")),"callback_form_required");
            RepairFlowRules.require(input.getCallbackTime()!=null&&input.getCallbackPerson()!=null&&!input.getCallbackPerson().isBlank()
                &&input.getSatisfaction()!=null&&input.getRework()!=null,"callback_form_required");
        }
    }
    private Mono<Void> applyDelay(RepairOrderEntity order,RepairContext context,RepairActionRequest input,Map<String,Object> flow){
        RepairFlowConfiguration.requireEnabled(flow);
        int count=order.getDelayCount()==null?0:order.getDelayCount();
        int limit=((Number)flow.getOrDefault("delayLimit",0)).intValue();
        RepairFlowRules.require(count<limit,"delay_limit");
        RepairFlowRules.require(input.getDelayUntil()!=null&&order.getFinishDeadline()!=null&&input.getDelayUntil()>order.getFinishDeadline(),"delay_time_invalid");
        return submit(order,context,input,"delay",text(flow,"delayRoleId"),null).doOnSuccess(ignore->order.setDelayCount(count+1));
    }
    private Mono<Void> changeLevel(RepairOrderEntity order,RepairContext context,RepairActionRequest input,Map<String,Object> flow){
        RepairFlowRules.require(input.getLevelId()!=null&&!Objects.equals(input.getLevelId(),order.getLevelId()),"different_level_required");
        return configurations.enabled(input.getLevelId(),"level",order.getBusinessProjectId(),context).flatMap(level->{
            Map<String,Object> data=level.getConfiguration()==null?Map.of():level.getConfiguration();
            int coefficient=((Number)data.getOrDefault("coefficient",1)).intValue();
            if(order.getLevelCoefficient()!=null&&coefficient<order.getLevelCoefficient()){
                RepairFlowConfiguration.requireEnabled(flow);
                RepairOrderEntity target=new RepairOrderEntity();applyLevel(target,level);
                if(input.getReason()==null||input.getReason().isBlank()){
                    input.setReason("报事等级由"+order.getLevelName()+"申请变更为"+level.getName());
                }
                return submit(order,context,input,"levelChange",text(flow,"levelChangeRoleId"),snapshot(target));
            }
            applyLevel(order,level);return Mono.empty();
        });
    }
    private Mono<Void> submit(RepairOrderEntity order,RepairContext context,RepairActionRequest input,String kind,String role,Map<String,Object> level){
        if(!"archive".equals(kind))RepairFlowRules.require(input.getReason()!=null&&!input.getReason().isBlank(),"reason_required");
        return reviews.createQuery().where(RepairReviewEntity::getOrderId,order.getId()).and(RepairReviewEntity::getStatus,1)
            .and(RepairReviewEntity::getKind,kind).count().map(count->count>0)
            .doOnNext(exists->RepairFlowRules.require(!exists,"review_pending"))
            .then(roles.assertAvailable(role,"review",order,context))
            .then(Mono.defer(()->{
                RepairReviewEntity review=new RepairReviewEntity();review.setId(IDGenerator.MD5.generate());
                review.setOrderId(order.getId());review.setTenantId(context.tenantId());review.setApplicationId(context.applicationId());
                review.setKind(kind);review.setRoleId(role);review.setStatus(1);review.setApplicantId(context.userId());review.setApplicantName(context.userName());
                review.setReason(input.getReason());review.setDelayUntil(input.getDelayUntil());review.setTargetLevel(level);
                review.setAttachmentIds(input.getAttachmentIds());review.setCreateTime(System.currentTimeMillis());
                return reviews.insert(review).then();
            }));
    }
    private Mono<Integer> decide(RepairOrderEntity order,RepairContext context,RepairActionRequest input){
        RepairFlowRules.require(input.getReviewId()!=null&&input.getApproved()!=null,"review_result_required");
        if(!input.getApproved())RepairFlowRules.require(input.getReason()!=null&&!input.getReason().isBlank(),"reason_required");
        return reviews.findById(input.getReviewId()).filter(review->Objects.equals(review.getOrderId(),order.getId()))
            .switchIfEmpty(Mono.error(new NotFoundException()))
            .flatMap(review->{
                RepairFlowRules.require(review.getStatus()==1,"review_already_decided");
                return roles.assertRole(review.getRoleId(),"review",order,context).then(Mono.defer(()->{
                    int next=reviewResult(order,review,Boolean.TRUE.equals(input.getApproved()));
                    return reviews.createUpdate().set(RepairReviewEntity::getStatus,input.getApproved()?2:3)
                        .set(RepairReviewEntity::getReviewerId,context.userId()).set(RepairReviewEntity::getReviewerName,context.userName())
                        .set(RepairReviewEntity::getRefuseReason,input.getApproved()?null:input.getReason())
                        .set(RepairReviewEntity::getOpinion,input.getDescription()).set(RepairReviewEntity::getReviewTime,System.currentTimeMillis())
                        .where(RepairReviewEntity::getId,review.getId()).and(RepairReviewEntity::getStatus,1).execute()
                        .doOnNext(count->RepairFlowRules.require(count==1,"version_conflict")).thenReturn(next);
                }));
            });
    }
    /** 审核通过与拒绝不合并：延期改时限，非关自动归档，废弃保留独立终态。 */
    static int reviewResult(RepairOrderEntity order,RepairReviewEntity review,boolean approved){
        if(!approved){if("archive".equals(review.getKind())){order.setFinishTime(null);order.setTimedOut(false);return 1;}return order.getStatus();}
        return switch(review.getKind()){
            case "delay" -> {order.setFinishDeadline(review.getDelayUntil());yield order.getStatus();}
            case "close" -> {order.setFinishTime(System.currentTimeMillis());yield 5;}
            case "abandon" -> {order.setAbandonTime(System.currentTimeMillis());order.setAbandonReason(review.getReason());yield 3;}
            case "levelChange" -> {restoreLevel(order,review.getTargetLevel());yield 1;}
            case "archive" -> 5;
            default -> throw new IllegalStateException("Unknown persisted review kind");
        };
    }
    static void applyLevel(RepairOrderEntity order,RepairConfigurationEntity level){
        Map<String,Object> data=level.getConfiguration()==null?Map.of():level.getConfiguration();
        order.setLevelId(level.getId());order.setLevelName(level.getName());order.setLevelColor(text(data,"color"));
        order.setLevelCoefficient(((Number)data.getOrDefault("coefficient",1)).intValue());
        order.setLevelAuditRequired(Boolean.TRUE.equals(data.get("auditRequired")));order.setLevelAuditRoleId(text(data,"auditRoleId"));
        order.setLevelThirdParty(Boolean.TRUE.equals(data.get("thirdParty")));
    }
    static Map<String,Object> snapshot(RepairOrderEntity order){
        Map<String,Object> data=new LinkedHashMap<>();data.put("id",order.getLevelId());data.put("name",order.getLevelName());
        data.put("coefficient",order.getLevelCoefficient());data.put("auditRequired",order.getLevelAuditRequired());
        data.put("auditRoleId",order.getLevelAuditRoleId());data.put("color",order.getLevelColor());data.put("thirdParty",order.getLevelThirdParty());return data;
    }
    private static void restoreLevel(RepairOrderEntity order,Map<String,Object> data){
        RepairConfigurationEntity level=new RepairConfigurationEntity();level.setId(text(data,"id"));level.setName(text(data,"name"));level.setConfiguration(data);applyLevel(order,level);
    }
    private static String text(Map<String,Object> data,String key){return Objects.toString(data.get(key),"");}
}
