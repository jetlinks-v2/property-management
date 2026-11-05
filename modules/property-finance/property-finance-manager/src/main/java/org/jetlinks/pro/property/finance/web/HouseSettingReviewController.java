package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.service.HouseSettingReviewService;
import org.jetlinks.pro.property.finance.service.HouseSettingService;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;

/** 审批沿用收费设置资源，独立按钮权限；服务中逐条检查项目、空间及版本。 */
@RestController
@RequestMapping("/property/finance/projects/{projectId}/settings/review")
@Resource(id = "property-finance-setting", name = "收费设置")
@Authorize
@RequiredArgsConstructor
public class HouseSettingReviewController {
    private final HouseSettingReviewService service;

    @PostMapping("/_approve")
    @ResourceAction(id = "approve", name = "审批通过")
    public Mono<List<HouseSettingService.SaveResult>> approve(
            @PathVariable String projectId, @RequestBody HouseSettingReviewService.Input input) {
        return service.decide(projectId, input, true, LocalDate.now());
    }

    @PostMapping("/_reject")
    @ResourceAction(id = "reject", name = "审批驳回")
    public Mono<List<HouseSettingService.SaveResult>> reject(
            @PathVariable String projectId, @RequestBody HouseSettingReviewService.Input input) {
        return service.decide(projectId, input, false, LocalDate.now());
    }
}
