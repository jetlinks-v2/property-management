package org.jetlinks.pro.property.quality.web;
import lombok.RequiredArgsConstructor;import org.springframework.web.bind.annotation.*;import jakarta.validation.Valid;
import org.hswebframework.web.authorization.annotation.*;import org.hswebframework.web.api.crud.entity.*;
import org.jetlinks.pro.property.quality.service.*;import org.jetlinks.pro.property.quality.entity.*;import org.jetlinks.pro.property.quality.api.*;import org.jetlinks.pro.smartpark.repair.api.*;
import reactor.core.publisher.*;import java.util.*;
/** PC 与 H5 共用同一工单接口，动作权限与工作流归属校验共同生效。 */
@RestController @RequiredArgsConstructor @RequestMapping("/quality/work-orders") @Resource(id="quality-work-order",name="品质工单")
public class QualityWorkOrderController {
 private final QualityWorkOrders access;private final QualityWorkOrderService orders;private final QualityWorkflow flow;private final QualityAssignees assignees;private final QualityRepairBridge repair;
 @PostMapping("/_query") @QueryAction public Mono<PagerResult<QualityWorkOrderEntity>> query(@RequestBody QueryParamEntity q,@RequestParam(defaultValue="pool")String mode){return access.personal(q,mode).flatMap(orders::queryPager);}
 @GetMapping("/{id}") @QueryAction public Mono<Map<String,Object>> get(@PathVariable String id){return access.detail(id);}
 @GetMapping("/{id}/candidates") @QueryAction public Flux<org.jetlinks.pro.property.base.user.PropertyUserInfo> candidates(@PathVariable String id,@RequestParam(defaultValue="inspect")String action){QualityScope.require(Set.of("inspect","rectify").contains(action),"unsupported_action");return access.read(id).flatMapMany(o->assignees.candidates(o,action,null));}
 @PostMapping("/{id}/draft") @ResourceAction(id="inspect",name="核查") public Mono<Void> draft(@PathVariable String id,@Valid @RequestBody QualityAction a){return flow.draft(id,a);}
 @PostMapping("/{id}/inspect") @ResourceAction(id="inspect",name="核查") public Mono<QualityWorkOrderEntity> inspect(@PathVariable String id,@Valid @RequestBody QualityAction a){return flow.inspect(id,a);}
 @PostMapping("/{id}/reassign") @ResourceAction(id="reassign",name="转派本人核查") public Mono<QualityWorkOrderEntity> reassign(@PathVariable String id,@Valid @RequestBody QualityAction a){return flow.reassign(id,a,false);}
 @PostMapping("/{id}/dispatch") @ResourceAction(id="dispatch",name="管理核查指派") public Mono<QualityWorkOrderEntity> dispatch(@PathVariable String id,@Valid @RequestBody QualityAction a){return flow.reassign(id,a,true);}
 @GetMapping("/repair/options/{projectId}") @ResourceAction(id="transferRepair",name="转报修") public Flux<QualityRepairOption> options(@PathVariable String projectId){return repair.options(projectId);}
 @PostMapping("/records/{id}/repair") @ResourceAction(id="transferRepair",name="转报修") public Mono<QualityRepairInfo> transfer(@PathVariable String id,@Valid @RequestBody QualityTransferRequest a){return repair.transfer(id,a);}
 @GetMapping("/records/{id}/repair") @ResourceAction(id="transferRepair",name="转报修") public Mono<QualityRepairInfo> linked(@PathVariable String id){return repair.linked(id);}
}
