package org.jetlinks.pro.property.decoration.web;

import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.hswebframework.web.authorization.annotation.*;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.jetlinks.pro.property.decoration.entity.*;
import org.jetlinks.pro.property.decoration.model.DecorationRequests;
import org.jetlinks.pro.property.decoration.service.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.base.house.PropertyHouseholdInfo;
import org.jetlinks.pro.property.base.user.PropertyEmployeeInfo;
import java.util.Map;
/** 公开基础只读目录；项目树保留层级，业务项目和SaaS会话入口编码分开。 */
@RestController @Authorize @RequiredArgsConstructor
@RequestMapping("/decoration/directory") @Resource(id="decoration-directory",name="装修办理目录")
public class DecorationDirectoryController {
    private final DecorationScope scope;
    private final DecorationConfigurationService configurations;
    private final DecorationFinanceService finance;
    private final DecorationRoleService roles;
    private final org.springframework.beans.factory.ObjectProvider<org.jetlinks.pro.property.decoration.model.DecorationChargeItemGateway> chargeItems;
    @PostMapping("/charge-items/{projectId}/_query") @ResourceAction(id="manage",name="查询收费科目")
    public Mono<PagerResult<org.jetlinks.pro.property.decoration.model.DecorationChargeItemGateway.ChargeItem>> chargeItems(@PathVariable String projectId, @RequestBody QueryParamEntity input) {
        return scope.current().then(scope.employee()).then(scope.project(projectId)).flatMap(project -> {
            var gateway=chargeItems.getIfAvailable();
            if(gateway==null)return Mono.error(DecorationRules.error("charge_directory_unavailable",503));
            var query=input.clone();query.setPaging(true);query.setPageSize(Math.max(1,Math.min(100,query.getPageSize())));query.setPageIndex(Math.max(0,query.getPageIndex()));
            return gateway.query(projectId,query).switchIfEmpty(Mono.error(DecorationRules.error("charge_directory_unavailable",503)));
        });
    }
    @GetMapping("/projects") @QueryAction
    public Flux<PropertyOrganizationInfo> projects() { return scope.current().thenMany(scope.tree()); }
    @GetMapping("/households/{projectId}") @ResourceAction(id="manage",name="管理端查询房屋关联")
    public Flux<PropertyHouseholdInfo> households(@PathVariable String projectId) { return scope.current().then(scope.employee()).thenMany(scope.households(projectId, false)); }
    @GetMapping("/mine/households/{projectId}") @QueryAction
    public Flux<PropertyHouseholdInfo> mine(@PathVariable String projectId) { return scope.current().thenMany(scope.households(projectId, true)); }
    @PostMapping("/employees/_query") @ResourceAction(id="manage",name="查询办理人员")
    public Mono<PagerResult<PropertyEmployeeInfo>> employees(@RequestBody QueryParamEntity query) { return scope.current().then(scope.employee()).then(scope.employees(query)); }
    @PostMapping("/roles/_query") @ResourceAction(id="manage",name="查询办理角色")
    public Mono<PagerResult<DecorationRoleService.Role>> roles(@RequestBody QueryParamEntity query) { return scope.current().then(scope.employee()).then(roles.query(query)); }
    @GetMapping("/capabilities") @QueryAction
    public Mono<Map<String, Boolean>> capabilities() { return scope.current().map(ctx -> finance.capabilities()); }
    @GetMapping("/form/{projectId}") @QueryAction
    public Mono<Map<String, Object>> form(@PathVariable String projectId, @RequestParam String spaceId, @RequestParam String customerId, @RequestParam String decorationType,
            @RequestParam(defaultValue="true") boolean mine) {
        return scope.current().flatMap(ctx -> (mine ? Mono.empty() : scope.employee().then(Mono.fromRunnable(() -> DecorationRules.require(ctx.authentication().hasPermission("decoration-directory", "manage"), "forbidden")))).then(scope.household(projectId, spaceId, customerId, mine))
            .flatMap(home -> Mono.zip(configurations.match(ctx, projectId, "agreement", home.houseType(), "", ""),
                configurations.match(ctx, projectId, "materials", home.houseType(), decorationType, ""))
                .map(tuple -> Map.<String,Object>of("household", home, "agreement", tuple.getT1().getContent().getAgreement(), "materials", tuple.getT2().getContent().getMaterials()))));
    }
}
