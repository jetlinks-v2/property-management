package org.jetlinks.pro.property.facility.web;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.facility.service.*;
import org.jetlinks.pro.property.finance.meter.MeterBillingModels;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/** Finance generation is only reachable through a versioned monthly review. */
@RestController @RequiredArgsConstructor
@RequestMapping("/property/facility-energy/configuration/meter-billing")
@Resource(id="facility-meter-billing",name="水电计费配置")
public class FacilityMeterBillingController {
  private final FacilityMeterCharges charges;
  private final FacilityMeterBilling billing;
  private final FacilityMeterReviews reviews;
  @GetMapping("/{projectId}/standards") @QueryAction
  public Mono<List<MeterBillingModels.Standard>> standards(@PathVariable String projectId) { return charges.standards(projectId); }
  @GetMapping("/{projectId}/rules") @QueryAction
  public Mono<List<MeterChargeEntity>> rules(@PathVariable String projectId) { return charges.rules(projectId); }
  @PostMapping("/rules") @SaveAction
  public Mono<MeterChargeEntity> save(@RequestBody FacilityMeterCharges.Association input) { return charges.associate(input); }
  @PostMapping("/{projectId}/bills/_query") @QueryAction
  public Mono<PagerResult<MeterBillingModels.Bill>> ledger(@PathVariable String projectId,@RequestBody QueryParamEntity input) {
    return billing.ledger(projectId,input);
  }
  @GetMapping("/{projectId}/bills/{id}") @ResourceAction(id="detail",name="查看计费明细")
  public Mono<MeterBillingModels.Bill> published(@PathVariable String projectId,@PathVariable String id) { return billing.published(projectId,id); }
  @PostMapping("/{projectId}/reviews/_query") @QueryAction
  public Mono<PagerResult<MeterBillEntity>> query(@PathVariable String projectId,@RequestParam(defaultValue="pending") String state,
    @RequestBody QueryParamEntity input) { return billing.reviews(projectId,state,input); }
  @GetMapping("/reviews/{id}") @ResourceAction(id="detail",name="查看计费明细")
  public Mono<MeterBillEntity> detail(@PathVariable String id) { return billing.detail(id); }
  @PostMapping("/reviews/{id}/logs/_query") @ResourceAction(id="detail",name="查看计费明细")
  public Mono<PagerResult<ProcessEntity>> logs(@PathVariable String id,@RequestBody QueryParamEntity input) { return billing.logs(id,input); }
  @PostMapping("/reviews/_review") @ResourceAction(id="review",name="审核水电账单")
  public Mono<List<FacilityMeterReviews.Outcome>> approve(@RequestBody List<FacilityMeterReviews.Decision> input) { return reviews.batch("review",input); }
  @PostMapping("/reviews/_reject") @ResourceAction(id="reject",name="驳回水电账单")
  public Mono<List<FacilityMeterReviews.Outcome>> reject(@RequestBody List<FacilityMeterReviews.Decision> input) { return reviews.batch("reject",input); }
  @PostMapping("/reviews/_recalculate") @ResourceAction(id="recalculate",name="重新核算水电账单")
  public Mono<List<FacilityMeterReviews.Outcome>> recalculate(@RequestBody List<FacilityMeterReviews.Decision> input) { return reviews.batch("recalculate",input); }
  @PostMapping("/reviews/_recover") @ResourceAction(id="recover",name="重试出账")
  public Mono<List<FacilityMeterReviews.Outcome>> recover(@RequestBody List<FacilityMeterReviews.Decision> input) { return reviews.batch("recover",input); }
}
