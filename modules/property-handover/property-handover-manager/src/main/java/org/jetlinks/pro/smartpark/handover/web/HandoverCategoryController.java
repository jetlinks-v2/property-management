package org.jetlinks.pro.smartpark.handover.web;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.smartpark.handover.entity.HandoverMaterialCategoryEntity;
import org.jetlinks.pro.smartpark.handover.service.HandoverMaterialCategoryService;
import org.jetlinks.pro.smartpark.handover.service.HandoverQuery;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 应用共享资料分类：不要求选择项目，仍校验应用资产、维护权限和版本。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/property/handover/category")
@Resource(id = "handover-config", name = "承接查验配置")
public class HandoverCategoryController {
    private final HandoverMaterialCategoryService service;
    @GetMapping("/_query") @QueryAction
    public Mono<PagerResult<HandoverMaterialCategoryEntity>> query(HandoverQuery input) {
        return service.query(input);
    }
    @GetMapping("/tree") @QueryAction
    public Flux<HandoverMaterialCategoryEntity> tree() { return service.tree(); }
    @GetMapping("/{id}") @QueryAction
    public Mono<HandoverMaterialCategoryEntity> read(@PathVariable String id) { return service.read(id); }
    @PostMapping @SaveAction
    public Mono<HandoverMaterialCategoryEntity> save(@RequestBody HandoverMaterialCategoryEntity input) {
        return service.save(input);
    }
    @DeleteMapping("/{id}") @DeleteAction
    public Mono<Void> delete(@PathVariable String id, @RequestParam Integer version) {
        return service.delete(id, version);
    }
}
