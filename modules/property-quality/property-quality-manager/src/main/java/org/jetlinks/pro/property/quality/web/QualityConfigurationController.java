package org.jetlinks.pro.property.quality.web;
import lombok.RequiredArgsConstructor;import org.springframework.web.bind.annotation.*;import jakarta.validation.Valid;
import org.hswebframework.web.authorization.annotation.*;import org.hswebframework.web.api.crud.entity.*;
import org.jetlinks.pro.property.quality.service.*;import org.jetlinks.pro.property.quality.entity.*;import reactor.core.publisher.*;
/** 配置与模板业务入口；只提供受控写入，不暴露继承框架的物理删除。 */
@RestController @RequiredArgsConstructor @RequestMapping("/quality/configurations") @Resource(id="quality-configuration",name="品质配置")
public class QualityConfigurationController {
 private final QualityConfigurationManager manager;private final QualityConfigurationService configs;
 @PostMapping("/_query") @QueryAction public Mono<PagerResult<QualityConfigurationEntity>> query(@RequestBody QueryParamEntity q){return manager.query(q).flatMap(configs::queryPager);}
 @GetMapping("/{id}") @QueryAction public Mono<QualityConfigurationEntity> get(@PathVariable String id){return manager.read(id);}
 @PostMapping @SaveAction public Mono<QualityConfigurationEntity> save(@Valid @RequestBody QualityConfigurationEntity r){return manager.save(r);}
 @PostMapping("/_company") @ResourceAction(id="companyConfigure",name="维护公司配置") public Mono<QualityConfigurationEntity> saveCompany(@Valid @RequestBody QualityConfigurationEntity r){return manager.save(r,true);}
 @PostMapping("/_delete") @DeleteAction public Mono<Void> deleteBatch(@RequestBody java.util.List<String> ids,@RequestParam(defaultValue="false")boolean company){return manager.deleteBatch(ids,company);}
 @DeleteMapping("/{id}") @DeleteAction public Mono<Void> delete(@PathVariable String id){return manager.delete(id);}
 @DeleteMapping("/_company/{id}") @ResourceAction(id="companyConfigure",name="维护公司配置") public Mono<Void> deleteCompany(@PathVariable String id){return manager.delete(id,true);}
}
