package org.jetlinks.pro.property.quality.web;
import lombok.RequiredArgsConstructor;import org.springframework.web.bind.annotation.*;import jakarta.validation.Valid;
import org.hswebframework.web.authorization.annotation.*;import org.jetlinks.pro.property.quality.service.*;import org.jetlinks.pro.property.quality.entity.*;import org.jetlinks.pro.property.quality.api.*;import reactor.core.publisher.Mono;
/** 整改和复验独立动作；实际执行人及原核查人由已保存的指派关系确定。 */
@RestController @RequiredArgsConstructor @RequestMapping("/quality/rectifications") @Resource(id="quality-work-order",name="品质工单")
public class QualityRectificationController {
 private final QualityWorkflow flow;
 @PostMapping("/{id}/submit") @ResourceAction(id="rectify",name="整改") public Mono<QualityWorkOrderEntity> submit(@PathVariable String id,@Valid @RequestBody QualityAction a){return flow.rectify(id,a);}
 @PostMapping("/{id}/reassign") @ResourceAction(id="rectify",name="整改") public Mono<QualityWorkOrderEntity> reassign(@PathVariable String id,@Valid @RequestBody QualityAction a){return flow.reassignRectification(id,a);}
 @PostMapping("/{id}/pass") @ResourceAction(id="review",name="复验") public Mono<QualityWorkOrderEntity> pass(@PathVariable String id,@Valid @RequestBody QualityAction a){return flow.review(id,a,true);}
 @PostMapping("/{id}/reject") @ResourceAction(id="review",name="复验") public Mono<QualityWorkOrderEntity> reject(@PathVariable String id,@Valid @RequestBody QualityAction a){return flow.review(id,a,false);}
}
