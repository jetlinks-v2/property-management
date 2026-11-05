package org.jetlinks.pro.property.facility.web;

import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.facility.model.*;
import org.jetlinks.pro.property.facility.service.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.*;

/** 业主最小查询权限，所有返回数据在本人有效绑定时间内裁剪。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/property/facility-energy/owner")
@Resource(id = "facility-owner", name = "本人仪表与消息")
public class FacilityOwnerController {

  private final FacilityOwner owner;

  @GetMapping("/meters")
  @QueryAction
  public Flux<Map<String, Object>> meters() {
    return owner.meters();
  }

  @GetMapping("/meters/{bindingId}")
  @QueryAction
  public Mono<Map<String, Object>> detail(
    @PathVariable String bindingId,
    @RequestParam long from,
    @RequestParam long to
  ) {
    return owner.detail(bindingId, from, to);
  }

  @GetMapping("/messages")
  @QueryAction
  public Flux<Map<String, Object>> messages() {
    return owner.messages();
  }

  @PostMapping("/messages/{id}/read")
  @QueryAction
  public Mono<Void> read(@PathVariable String id) {
    return owner.markRead(id);
  }
}
