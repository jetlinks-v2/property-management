package org.jetlinks.pro.property.facility.web;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.facility.entity.ProcessEntity;
import org.jetlinks.pro.property.facility.service.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/** Application audit is an administrator-only capability scoped to authorized business projects. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/property/facility-energy/operations")
@Resource(id = "facility-operation", name = "设施能源操作日志")
public class FacilityOperationController {

  private final ProcessStore store;
  private final FacilityQueries queries;

  @PostMapping("/_query")
  @QueryAction
  public Mono<PagerResult<ProcessEntity>> query(
    @RequestBody QueryParamEntity query
  ) {
    return queries.projects(store, query);
  }
}
