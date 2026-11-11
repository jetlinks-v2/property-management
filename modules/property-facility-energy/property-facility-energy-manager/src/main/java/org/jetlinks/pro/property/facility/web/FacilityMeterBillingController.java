package org.jetlinks.pro.property.facility.web;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.facility.entity.MeterChargeEntity;
import org.jetlinks.pro.property.facility.service.*;
import org.jetlinks.pro.property.finance.command.ProcessMeterBillCommand;
import org.jetlinks.pro.property.finance.meter.MeterBillingModels;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController @RequiredArgsConstructor
@RequestMapping("/property/facility-energy/configuration/meter-billing")
@Resource(id = "facility-meter-billing", name = "水电计费配置")
public class FacilityMeterBillingController {
  private final FacilityMeterCharges charges;
  private final FacilityMeterBilling billing;
  @GetMapping("/{projectId}/standards") @QueryAction
  public Mono<List<MeterBillingModels.Standard>> standards(@PathVariable String projectId) { return charges.standards(projectId); }
  @GetMapping("/{projectId}/rules") @QueryAction
  public Mono<List<MeterChargeEntity>> rules(@PathVariable String projectId) { return charges.rules(projectId); }
  @PostMapping("/rules") @SaveAction
  public Mono<MeterChargeEntity> save(@RequestBody MeterChargeEntity input) { return charges.save(input); }
  @PostMapping("/preview") @QueryAction
  public Mono<PagerResult<FacilityMeterBilling.Row>> preview(@RequestBody FacilityMeterBilling.Preview input) { return billing.preview(input); }
  @PostMapping("/generate") @ResourceAction(id = "generate", name = "生成应收")
  public Mono<List<FacilityMeterBilling.Row>> generate(@RequestBody List<ProcessMeterBillCommand> inputs) { return billing.generate(inputs); }
}
