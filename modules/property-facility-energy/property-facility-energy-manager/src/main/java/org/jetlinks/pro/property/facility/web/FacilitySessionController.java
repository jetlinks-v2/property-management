package org.jetlinks.pro.property.facility.web;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.jetlinks.pro.property.facility.service.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/** 仅返回当前会话身份，业主无需获得设备通用查询权限。 */
@RestController
@RequiredArgsConstructor
@Authorize
public class FacilitySessionController {

  private final FacilityScope scope;

  @GetMapping("/property/facility-energy/session")
  public Mono<FacilityContext> session() {
    return scope.current();
  }
}
