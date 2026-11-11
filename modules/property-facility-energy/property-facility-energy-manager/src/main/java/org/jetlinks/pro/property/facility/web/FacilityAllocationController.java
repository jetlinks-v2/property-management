package org.jetlinks.pro.property.facility.web;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.facility.entity.DeviceEntity;
import org.jetlinks.pro.property.facility.service.FacilityDevices;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
@RequestMapping("/property/facility-energy/configuration/devices")
@Resource(id = "facility-device", name = "设施设备")
public class FacilityAllocationController {
  private final FacilityDevices devices;
  @PostMapping("/_query") @QueryAction
  public Mono<PagerResult<DeviceEntity>> query(@RequestBody QueryParamEntity query) { return devices.allocations(query); }
  @PostMapping @SaveAction
  public Mono<List<DeviceEntity>> allocate(@RequestBody List<FacilityDevices.Allocation> inputs) { return devices.allocate(inputs); }
}
