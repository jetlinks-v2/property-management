package org.jetlinks.pro.smartpark.repair.web;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.hswebframework.web.authorization.annotation.*;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.service.ReactiveCrudService;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.crud.CorrelatesAssetsHolderQueryController;
import org.jetlinks.pro.smartpark.repair.entity.*;
import org.jetlinks.pro.smartpark.repair.api.*;
import org.jetlinks.pro.smartpark.repair.service.*;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import java.util.function.Function;
/** 工单/配置查询保留框架 QueryParam、分页与资产注入，并限定可信应用范围。 */
@RestController @AllArgsConstructor @RequestMapping("/repair/orders")
@Resource(id="repair-order",name="报事工单")
public class RepairOrderController implements CorrelatesAssetsHolderQueryController<RepairOrderEntity,String>{
 private final RepairContextProvider contexts;
 private final org.jetlinks.pro.property.service.TenantAssetScope tenantScope;
 private final RepairOrderService service;
 private final RepairOrderManager manager;
 private final RepairReportService reports;
 private final RepairExportService exports;
 private final RepairReviewQueryService reviewQueries;
 private final RepairDeviceService devices;
 private final RepairRelatedQuery related;
 private final RepairTaskQuery tasks;
 @Override public AssetType getAssetType(){return AssetType.of("repairOrder");}
 @Override public Function<RepairOrderEntity,?> getAssetIdMapper(){return RepairOrderEntity::getId;}
 @Override public String getAssetProperty(){return "id";}
 @Override public ReactiveCrudService<RepairOrderEntity,String> getService(){return service;}
 @Override public Mono<QueryParamEntity> injectQueryParam(QueryParamEntity query){
  return contexts.current().flatMap(ctx->tasks.resolve(query,ctx).flatMap(expanded->related.resolve(expanded,ctx))
   .flatMap(expanded->CorrelatesAssetsHolderQueryController.super.injectQueryParam(expanded.toNestQuery().and("applicationId",ctx.applicationId()).getParam()))
   .flatMap(scoped -> tenantScope.injectQuery(scoped,getAssetType(),getAssetProperty())));
 }
 @PostMapping("/_exports") @ResourceAction(id="export",name="导出报事")
 public Mono<RepairExportEntity> export(@RequestBody QueryParamEntity query,
   @RequestParam(defaultValue="orders") RepairExportType type,@RequestParam(defaultValue="xlsx") String format,
   @RequestParam(required=false) Integer reviewStatus,@RequestParam(required=false) String reviewKind){
  return injectQueryParam(query).flatMap(scoped->exports.create(scoped,type,format,reviewStatus,reviewKind));
 }
 @PostMapping("/_exports/_query") @ResourceAction(id="export",name="导出报事")
 public Mono<org.hswebframework.web.api.crud.entity.PagerResult<RepairExportEntity>> exportHistory(@RequestBody QueryParamEntity query){
  return exports.query(query);
 }
 @GetMapping("/_exports/{id}/download") @ResourceAction(id="export",name="导出报事")
 public Mono<org.springframework.http.ResponseEntity<Flux<org.springframework.core.io.buffer.DataBuffer>>> download(@PathVariable String id){
  return exports.visible(id).flatMap(exports::authorizeDownload).map(task->org.springframework.http.ResponseEntity.ok()
   .header("Content-Disposition",org.springframework.http.ContentDisposition.attachment().filename(task.getName(),java.nio.charset.StandardCharsets.UTF_8).build().toString())
   .contentType(org.springframework.http.MediaType.parseMediaType(task.getName().endsWith(".xlsx")
      ?"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":"text/csv;charset=UTF-8")).body(exports.download(task)));
 }
 /** 统计与列表共用同一查询注入边界，客户端OR条件不能放宽租户与应用范围。 */
 @PostMapping("/_statistics") @QueryAction
 public Flux<RepairReportRow> statistics(@RequestBody QueryParamEntity query){
  return injectQueryParam(query).flatMapMany(reports::summary);
 }
 /** 页签统计沿用列表的完整应用、租户、资产条件注入。 */
 @PostMapping("/_status-counts") @QueryAction
 public Flux<RepairReportRow> statusCounts(@RequestBody QueryParamEntity query){
  return injectQueryParam(query).flatMapMany(reports::statusCounts);
 }
 @PostMapping("/_reviews/_query") @QueryAction
 public Mono<org.hswebframework.web.api.crud.entity.PagerResult<RepairReviewOrderRow>> queryReviews(@RequestBody QueryParamEntity query,
   @RequestParam(required=false) Integer reviewStatus,@RequestParam(required=false) String reviewKind){
  return injectQueryParam(query).flatMap(scoped->reviewQueries.query(scoped,reviewStatus,reviewKind));
 }
 @PostMapping("/_devices/{businessProjectId}/_query") @ResourceAction(id="create",name="发起报事")
 public Mono<org.hswebframework.web.api.crud.entity.PagerResult<RepairDeviceOption>> queryDevices(@PathVariable String businessProjectId,@RequestBody QueryParamEntity query){
  return devices.query(businessProjectId,query);
 }
 @PostMapping("/_dashboard") @QueryAction
 public Mono<RepairDashboard> dashboard(@RequestBody QueryParamEntity query,@RequestParam(defaultValue="day") String interval){
  return injectQueryParam(query).flatMap(scoped->reports.dashboard(scoped,interval));
 }
 @PostMapping @ResourceAction(id="create",name="发起报事")
 public Mono<RepairOrderEntity> create(@jakarta.validation.Valid @RequestBody CreateRepairRequest request){return manager.create(request);}
 @Override @GetMapping("/{id}") @QueryAction
 public Mono<RepairOrderEntity> getById(@PathVariable String id){return manager.read(id);}
 @GetMapping("/{id}/history") @QueryAction
 public Flux<RepairProcessEntity> history(@PathVariable String id){return manager.history(id);}
 @GetMapping("/{id}/assignees") @ResourceAction(id="dispatch",name="分派")
 public Flux<RepairAssignee> assignees(@PathVariable String id,@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="0") int limit){return manager.assignees(id,search,limit);}
 @PostMapping("/{id}/actions/dispatch") @ResourceAction(id="dispatch",name="分派")
 public Mono<RepairOrderEntity> dispatch(@PathVariable String id,@jakarta.validation.Valid @RequestBody RepairActionRequest request){return manager.action(id,"dispatch",request);}
 @PostMapping("/{id}/actions/accept") @ResourceAction(id="accept",name="接单")
 public Mono<RepairOrderEntity> accept(@PathVariable String id,@jakarta.validation.Valid @RequestBody RepairActionRequest request){return manager.action(id,"accept",request);}
 @PostMapping("/{id}/actions/follow") @ResourceAction(id="follow",name="跟进")
 public Mono<RepairOrderEntity> follow(@PathVariable String id,@jakarta.validation.Valid @RequestBody RepairActionRequest request){return manager.action(id,"follow",request);}
 @PostMapping("/{id}/actions/finish") @ResourceAction(id="finish",name="完结")
 public Mono<RepairOrderEntity> finish(@PathVariable String id,@jakarta.validation.Valid @RequestBody RepairActionRequest request){return manager.action(id,"finish",request);}
 @PostMapping("/{id}/actions/callback") @ResourceAction(id="callback",name="回访")
 public Mono<RepairOrderEntity> callback(@PathVariable String id,@jakarta.validation.Valid @RequestBody RepairActionRequest request){return manager.action(id,"callback",request);}
 @GetMapping("/{id}/reviews") @QueryAction
 public Flux<RepairReviewEntity> reviews(@PathVariable String id){return manager.reviews(id);}
 @GetMapping("/{id}/transfer-assignees") @ResourceAction(id="transfer",name="转派")
 public Flux<RepairAssignee> transferAssignees(@PathVariable String id,@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="0") int limit){return manager.assignees(id,search,limit);}
 @PostMapping("/{id}/actions/transfer") @ResourceAction(id="transfer",name="转派")
 public Mono<RepairOrderEntity> transfer(@PathVariable String id,@jakarta.validation.Valid @RequestBody RepairActionRequest request){return manager.action(id,"transfer",request);}
 @PostMapping("/{id}/actions/grab") @ResourceAction(id="grab",name="抢单")
 public Mono<RepairOrderEntity> grab(@PathVariable String id,@jakarta.validation.Valid @RequestBody RepairActionRequest request){return manager.action(id,"grab",request);}
 @PostMapping("/{id}/actions/delay") @ResourceAction(id="delay",name="申请延期")
 public Mono<RepairOrderEntity> delay(@PathVariable String id,@jakarta.validation.Valid @RequestBody RepairActionRequest request){return manager.action(id,"delay",request);}
 @PostMapping("/{id}/actions/close") @ResourceAction(id="close",name="非正常关单申请")
 public Mono<RepairOrderEntity> close(@PathVariable String id,@jakarta.validation.Valid @RequestBody RepairActionRequest request){return manager.action(id,"close",request);}
 @PostMapping("/{id}/actions/changeLevel") @ResourceAction(id="changeLevel",name="等级变更")
 public Mono<RepairOrderEntity> changeLevel(@PathVariable String id,@jakarta.validation.Valid @RequestBody RepairActionRequest request){return manager.action(id,"changeLevel",request);}
 @PostMapping("/{id}/actions/changeCategory") @ResourceAction(id="changeCategory",name="修改分类")
 public Mono<RepairOrderEntity> changeCategory(@PathVariable String id,@jakarta.validation.Valid @RequestBody RepairActionRequest request){return manager.action(id,"changeCategory",request);}
 @PostMapping("/{id}/actions/abandonApply") @ResourceAction(id="abandonApply",name="申请废弃")
 public Mono<RepairOrderEntity> abandonApply(@PathVariable String id,@jakarta.validation.Valid @RequestBody RepairActionRequest request){return manager.action(id,"abandonApply",request);}
 @PostMapping("/{id}/actions/review") @ResourceAction(id="review",name="审核")
 public Mono<RepairOrderEntity> review(@PathVariable String id,@jakarta.validation.Valid @RequestBody RepairActionRequest request){return manager.action(id,"review",request);}
 @PostMapping("/{id}/actions/evaluate") @ResourceAction(id="evaluate",name="员工评价")
 public Mono<RepairOrderEntity> evaluate(@PathVariable String id,@jakarta.validation.Valid @RequestBody RepairActionRequest request){return manager.action(id,"evaluate",request);}
 @PostMapping("/{id}/actions/urge") @ResourceAction(id="urge",name="催办")
 public Mono<RepairOrderEntity> urge(@PathVariable String id,@jakarta.validation.Valid @RequestBody RepairActionRequest request){return manager.action(id,"urge",request);}
 @PostMapping("/{id}/actions/toPaid") @ResourceAction(id="toPaid",name="转为有偿报事")
 public Mono<RepairOrderEntity> toPaid(@PathVariable String id,@jakarta.validation.Valid @RequestBody RepairActionRequest request){return manager.action(id,"toPaid",request);}
 @PostMapping("/{id}/actions/quote") @ResourceAction(id="quote",name="提交费用待签字")
 public Mono<RepairOrderEntity> quote(@PathVariable String id,@jakarta.validation.Valid @RequestBody RepairActionRequest request){return manager.action(id,"quote",request);}
 @PostMapping("/{id}/actions/sign") @ResourceAction(id="sign",name="登记业主签字")
 public Mono<RepairOrderEntity> sign(@PathVariable String id,@jakarta.validation.Valid @RequestBody RepairActionRequest request){return manager.action(id,"sign",request);}
 @PostMapping("/{id}/actions/ownerEvaluate") @ResourceAction(id="ownerEvaluate",name="登记业主评价")
 public Mono<RepairOrderEntity> ownerEvaluate(@PathVariable String id,@jakarta.validation.Valid @RequestBody RepairActionRequest request){return manager.action(id,"ownerEvaluate",request);}
}
