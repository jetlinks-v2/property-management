package org.jetlinks.pro.property.facility.web;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.facility.entity.DeviceEntity;
import org.jetlinks.pro.property.facility.service.FacilityDevices;
import org.jetlinks.pro.property.facility.service.FacilityAllocations;
import org.jetlinks.pro.property.facility.entity.DeviceAllocationEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
@RequestMapping("/property/facility-energy/configuration/devices")
@Resource(id = "facility-device", name = "设施设备")
public class FacilityAllocationController {
  private final FacilityAllocations devices;
  @PostMapping("/_query") @QueryAction
  public Mono<PagerResult<DeviceAllocationEntity>> query(@RequestBody QueryParamEntity query) { return devices.query(query); }
  @PostMapping @SaveAction
  public Mono<List<DeviceAllocationEntity>> allocate(@RequestBody List<FacilityDevices.Allocation> inputs) { return devices.save(inputs); }
  @PostMapping("/_refresh") @SaveAction
  public Mono<Integer> refresh() { return devices.refresh(); }
}
