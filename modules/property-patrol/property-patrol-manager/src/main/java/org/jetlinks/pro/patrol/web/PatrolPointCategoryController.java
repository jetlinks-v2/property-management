package org.jetlinks.pro.patrol.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.jetlinks.pro.assets.annotation.AssetsController;
import org.jetlinks.pro.patrol.service.PatrolPointCategoryService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import java.util.List;

/** 分类关联复用点位配置功能权限，服务同时拥有点位、分类及受影响计划的资产检查。 */
@RestController
@RequestMapping("/park/patrol/point-categories")
@Resource(id = "patrol-point", name = "巡更点位")
@AssetsController(ignore = true)
@AllArgsConstructor
public class PatrolPointCategoryController {
    private final PatrolPointCategoryService service;
    public record Selection(@NotEmpty @Size(max = 100) List<@NotBlank String> pointIds,
                            @NotBlank @Size(max = 64) String categoryId) {}
    @PostMapping("/link")
    @SaveAction
    public Mono<Void> link(@RequestBody @Valid Selection selection) {
        return service.link(selection.pointIds(), selection.categoryId());
    }
    @PostMapping("/cancel")
    @SaveAction
    public Mono<Void> cancel(@RequestBody @Valid Selection selection) {
        return service.cancel(selection.pointIds(), selection.categoryId());
    }
}
