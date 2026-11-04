package org.jetlinks.pro.smartpark.repair.service;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.jetlinks.pro.smartpark.repair.api.RepairActionRequest;
import java.util.Objects;
import java.util.Set;
/** 旧流程可验证的状态规则；服务层负责身份、配置和持久化，纯规则不访问数据库。 */
public final class RepairFlowRules {
 private RepairFlowRules(){}
 public static int nextStatus(RepairOrderEntity order,String action,String actor,RepairActionRequest request,boolean callbackRequired){
  int status=order.getStatus();
  return switch(action){
   case "dispatch" -> {require(status==0,"invalid_state");yield 6;}
   case "accept" -> {require(status==6,"invalid_state");require(Objects.equals(actor,order.getAssignedUserId())
    || (order.getAssignedUserId()==null&&order.getCandidateRoleId()!=null),"not_assignee");yield 7;}
   case "follow" -> {require(status==7||status==1,"invalid_state");require(Objects.equals(actor,order.getAssignedUserId()),"not_assignee");
    if(status==7){require(request.getAttachmentIds()!=null&&!request.getAttachmentIds().isEmpty(),"arrival_photo_required");require(request.getLevelId()!=null&&!request.getLevelId().isBlank(),"level_required");}yield 1;}
   case "finish" -> {require(status==1,"invalid_state");require(Objects.equals(actor,order.getAssignedUserId()),"not_assignee");require(!Boolean.TRUE.equals(order.getPaid())||Boolean.TRUE.equals(order.getSigned()),"signature_required");yield callbackRequired?8:archiveStatus(order);}
   case "callback" -> {require(status==8||status==2,"invalid_state");yield Boolean.TRUE.equals(request.getRework())?1:archiveStatus(order);}
   case "transfer" -> {require(Set.of(6,7,1).contains(status),"invalid_state");require(!Objects.equals(order.getAssignedUserId(),request.getAssignedUserId()),"same_assignee");yield 6;}
   case "grab" -> {require(status==0,"invalid_state");yield 7;}
   case "delay", "close", "changeLevel" -> {require(status==1,"invalid_state");require(Objects.equals(actor,order.getAssignedUserId()),"not_assignee");yield status;}
   case "changeCategory" -> {require(Set.of(0,1,6,7).contains(status),"invalid_state");yield status;}
   case "abandonApply" -> {require(Set.of(0,1,6,7).contains(status),"invalid_state");yield status;}
   case "urge" -> {require(Set.of(0,1,6,7,8,9).contains(status),"invalid_state");yield status;}
   case "evaluate" -> {require(Set.of(2,8,5).contains(status),"invalid_state");require(!Boolean.TRUE.equals(order.getStaffEvaluated()),"already_evaluated");yield status;}
   case "ownerEvaluate" -> {require(Set.of(2,8,5).contains(status),"invalid_state");require(!Boolean.TRUE.equals(order.getOwnerEvaluated()),"owner_already_evaluated");yield status;}
   case "toPaid" -> {require(status==1,"invalid_state");require(Objects.equals(actor,order.getAssignedUserId()),"not_assignee");require(!Boolean.TRUE.equals(order.getPaid()),"already_paid");yield status;}
   case "quote" -> {require(status==1&&Boolean.TRUE.equals(order.getPaid()),"invalid_state");require(Objects.equals(actor,order.getAssignedUserId()),"not_assignee");require(!Boolean.TRUE.equals(order.getSignatureRequested()),"quote_already_submitted");yield status;}
   case "sign" -> {require(status==1&&Boolean.TRUE.equals(order.getSignatureRequested()),"invalid_state");require(!Boolean.TRUE.equals(order.getSigned()),"already_signed");yield status;}
   case "review" -> status; // 类型、角色和结果由持久化审核快照决定。
   default -> throw new BusinessException("error.repair.unsupported_action",400);
  };
 }
 public static int archiveStatus(RepairOrderEntity order){return Boolean.TRUE.equals(order.getLevelAuditRequired())?9:5;}
 public static void require(boolean valid,String code){
  if(!valid){
   int status=Set.of("version_conflict","request_conflict","configuration_changed").contains(code)?409:400;
   throw new BusinessException("error.repair."+code,status);
  }
 }
}
