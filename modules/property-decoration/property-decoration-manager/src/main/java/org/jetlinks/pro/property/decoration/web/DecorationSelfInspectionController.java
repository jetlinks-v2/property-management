package org.jetlinks.pro.property.decoration.web;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.decoration.entity.DecorationSelfInspectionEntity;
import org.jetlinks.pro.property.decoration.service.DecorationSelfInspectionService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/** PC 自检只读和逻辑删除接口，不开放独立新增或通用实体写入。 */
@RestController @Authorize @RequiredArgsConstructor
@RequestMapping("/decoration/self-inspections") @Resource(id="decoration-application", name="装修申请")
public class DecorationSelfInspectionController {
    private final DecorationSelfInspectionService inspections;
    @PostMapping("/_query") @QueryAction
    public Mono<PagerResult<DecorationSelfInspectionEntity>> query(@RequestBody QueryParamEntity input) { return inspections.query(input); }
    @GetMapping("/{id}") @QueryAction
    public Mono<DecorationSelfInspectionEntity> read(@PathVariable String id) { return inspections.read(id); }
    @DeleteMapping("/{id}") @DeleteAction
    public Mono<Void> delete(@PathVariable String id, @RequestParam long version) { return inspections.delete(id, version); }
}
