package org.jetlinks.pro.property.quality.web;
import lombok.RequiredArgsConstructor;import org.springframework.web.bind.annotation.*;import org.hswebframework.web.authorization.annotation.*;
import org.hswebframework.web.authorization.Authentication;import org.jetlinks.pro.property.quality.service.*;import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;import org.jetlinks.pro.property.base.user.PropertyUserInfo;import reactor.core.publisher.*;import java.util.*;
/** 仅提供当前应用有权消费的共享基础目录，系统管理仍直接使用同一 /property 接口和基础表。 */
@RestController @RequiredArgsConstructor @RequestMapping("/quality/context") @Resource(id="quality-work-order",name="品质工单")
public class QualityContextController {
 private final QualityScope scope;private final QualityDirectory directory;private final QualityTaskCandidates candidates;
 @GetMapping @QueryAction public Mono<Map<String,Object>> context(){return scope.current().flatMap(c->Authentication.currentReactive().map(auth->Map.of("userId",c.userId(),"userName",c.userName(),"applicationId",c.applicationId(),"tenantId",c.tenantId(),"actions",List.of("query","inspect","rectify","review","dispatch","reassign","transferRepair","export").stream().filter(a->auth.hasPermission("quality-work-order",a)).toList())));}
 @GetMapping("/projects") @QueryAction public Flux<PropertyOrganizationInfo> projects(){return scope.current().thenMany(directory.projects());}
 @GetMapping("/employees") @QueryAction public Flux<PropertyUserInfo> employees(){return scope.current().thenMany(directory.employees());}
 @GetMapping("/assignment-candidates") @QueryAction public Flux<PropertyUserInfo> assignmentCandidates(@RequestParam String projectId,@RequestParam(required=false)String role){return candidates.candidates(projectId,role);}
 @GetMapping("/roles") @QueryAction public Flux<Map<String,String>> roles(@RequestParam String projectId){return candidates.roles(projectId);}
}
