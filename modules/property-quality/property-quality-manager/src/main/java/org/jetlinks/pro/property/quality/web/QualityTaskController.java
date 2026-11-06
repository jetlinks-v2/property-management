package org.jetlinks.pro.property.quality.web;
import lombok.RequiredArgsConstructor;import org.springframework.web.bind.annotation.*;import jakarta.validation.Valid;
import org.hswebframework.web.authorization.annotation.*;import org.hswebframework.web.api.crud.entity.*;
import org.jetlinks.pro.property.quality.service.*;import org.jetlinks.pro.property.quality.entity.*;import reactor.core.publisher.*;
/** 任务定义与下发入口，读写统一受任务资产权限控制。 */
@RestController @RequiredArgsConstructor @RequestMapping("/quality/tasks") @Resource(id="quality-task",name="品质任务")
public class QualityTaskController {
 private final QualityTaskManager manager;private final QualityTaskService tasks;private final QualityScheduleService schedules;
 @PostMapping("/_query") @QueryAction public Mono<PagerResult<QualityTaskEntity>> query(@RequestBody QueryParamEntity q){return manager.query(q).flatMap(tasks::queryPager);}
 @GetMapping("/{id}") @QueryAction public Mono<QualityTaskEntity> get(@PathVariable String id){return manager.read(id);}
 @PostMapping @SaveAction public Mono<QualityTaskEntity> save(@Valid @RequestBody QualityTaskEntity r){return manager.save(r);}
 @PostMapping("/_delete") @DeleteAction public Mono<Void> deleteBatch(@RequestBody java.util.List<String> ids){return manager.deleteBatch(ids);}
 @DeleteMapping("/{id}") @DeleteAction public Mono<Void> delete(@PathVariable String id){return manager.delete(id);}
 @PostMapping("/{id}/issue") @ResourceAction(id="issue",name="下发") public Mono<QualityTaskEntity> issue(@PathVariable String id){return manager.issue(id);}
 @PostMapping("/{id}/close") @ResourceAction(id="close",name="关闭") public Mono<QualityTaskEntity> close(@PathVariable String id){return manager.close(id);}
 @GetMapping("/{id}/schedules") @QueryAction public Flux<QualityScheduleEntity> schedules(@PathVariable String id){return manager.read(id).thenMany(schedules.createQuery().where("taskId",id).fetch());}
}
