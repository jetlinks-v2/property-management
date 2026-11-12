package org.jetlinks.pro.property.facility.web;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.facility.entity.NotificationEntity;
import org.jetlinks.pro.property.facility.service.FacilityInbox;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
@RequestMapping("/property/facility-energy/messages")
@Resource(id = "facility-message", name = "员工消息")
public class FacilityMessageController {
  private final FacilityInbox inbox;
  @PostMapping("/records/_query") @ResourceAction(id = "records", name = "员工消息记录")
  public Mono<PagerResult<NotificationEntity>> records(@RequestBody QueryParamEntity query) {
    return inbox.records(query);
  }
  private final org.jetlinks.pro.property.facility.service.FacilityNotificationPolicies policies;
  @GetMapping("/settings/{projectId}") @ResourceAction(id = "settings", name = "通知设置")
  public Mono<org.jetlinks.pro.property.facility.entity.NotificationPolicyEntity> settings(@PathVariable String projectId) { return policies.read(projectId); }
  @PostMapping("/settings") @ResourceAction(id = "settings", name = "通知设置")
  public Mono<org.jetlinks.pro.property.facility.entity.NotificationPolicyEntity> settings(@RequestBody org.jetlinks.pro.property.facility.entity.NotificationPolicyEntity input) { return policies.save(input); }
  @PostMapping("/_query") @QueryAction
  public Mono<PagerResult<NotificationEntity>> page(@RequestBody QueryParamEntity query) { return inbox.page(query); }
  @GetMapping("/unread") @QueryAction
  public Mono<Integer> unread(@RequestParam(required = false) String projectId) { return inbox.unread(projectId); }
  @PostMapping("/read-all") @QueryAction
  public Mono<Void> readAll(@RequestParam(required = false) String projectId) { return inbox.read(null, projectId); }
  @PostMapping("/{id}/read") @QueryAction
  public Mono<Void> read(@PathVariable String id) { return inbox.read(id, null); }
}
