package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.base.archive.*;
import org.jetlinks.pro.property.finance.service.*;
import org.jetlinks.pro.property.finance.setting.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.*;

/** 项目收费设置独立授权；房屋、客户和标准选择不要求额外的维护菜单权限。 */
@RestController
@RequestMapping("/property/finance/projects/{projectId}/settings")
@Resource(id = "property-finance-setting", name = "收费设置")
@Authorize
@RequiredArgsConstructor
public class HouseSettingController {
    private final HouseSettingService service;
    private final ReceivableGenerationService generation;

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<SettingView>> query(
            @PathVariable String projectId, @RequestBody QueryParamEntity query) {
        return service.query(projectId, query);
    }

    @GetMapping("/{id}")
    @ResourceAction(id = "query", name = "查询")
    public Mono<SettingView> detail(@PathVariable String projectId, @PathVariable String id) {
        return service.detail(projectId, id);
    }

    @GetMapping("/_options")
    @ResourceAction(id = "query", name = "查询")
    public Mono<Map<String, Object>> options(@PathVariable String projectId) {
        return service.options(projectId);
    }

    @GetMapping("/houses")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PropertyHousePage> houses(
            @PathVariable String projectId,
            @RequestParam(required = false) String spaceId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int pageIndex,
            @RequestParam(defaultValue = "12") int pageSize) {
        return service.houses(projectId, spaceId, keyword, pageIndex, pageSize);
    }

    @GetMapping("/houses/{spaceId}")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PropertyHouseContext> context(
            @PathVariable String projectId, @PathVariable String spaceId) {
        return service.houseContext(projectId, spaceId);
    }

    @PostMapping
    @ResourceAction(id = "save", name = "保存")
    public Mono<HouseSettingService.SaveResult> create(
            @PathVariable String projectId, @RequestBody SettingInput input) {
        return service.create(projectId, input, LocalDate.now());
    }

    @PostMapping("/_batch")
    @ResourceAction(id = "save", name = "保存")
    public Mono<List<HouseSettingService.SaveResult>> batch(
            @PathVariable String projectId, @RequestBody List<SettingInput> inputs) {
        return service.createBatch(projectId, inputs, LocalDate.now());
    }

    @PutMapping("/{id}")
    @ResourceAction(id = "save", name = "保存")
    public Mono<HouseSettingService.SaveResult> update(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody SettingInput input) {
        return service.update(projectId, id, input, LocalDate.now());
    }

    @PostMapping("/{id}/_next-version")
    @ResourceAction(id = "save", name = "保存")
    public Mono<HouseSettingService.SaveResult> nextVersion(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody SettingInput input) {
        return service.nextVersion(projectId, id, input, LocalDate.now());
    }

    @PostMapping("/{id}/_stop")
    @ResourceAction(id = "save", name = "保存")
    public Mono<SettingView> stop(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody HouseSettingService.StopInput input) {
        return service.stop(projectId, id, input, LocalDate.now());
    }

    @DeleteMapping("/{id}")
    @ResourceAction(id = "delete", name = "删除")
    public Mono<Void> delete(
            @PathVariable String projectId, @PathVariable String id, @RequestParam Long version) {
        return service.delete(projectId, id, version);
    }

    @PostMapping("/{id}/_generate")
    @ResourceAction(id = "calculate", name = "生成应收")
    public Mono<ReceivableGenerationService.GenerationResult> generate(
            @PathVariable String projectId, @PathVariable String id) {
        return generation.generate(projectId, id, LocalDate.now());
    }
}
