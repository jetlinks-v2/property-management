package org.jetlinks.pro.patrol.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.jetlinks.pro.patrol.service.PatrolMobileService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.time.LocalDate;
import java.util.List;

/** 复用任务查询功能权限，移动入口不创建第二套授权体系。 */
@RestController
@AllArgsConstructor
@RequestMapping("/park/patrol/mobile/tasks")
@Resource(id = "patrol-task", name = "巡更任务")
public class PatrolMobileTaskController {
    private final PatrolMobileService mobile;
    public record Request(@NotNull LocalDate date, Boolean mine, @jakarta.validation.constraints.Size(max = 128) String nfcCode) {}
    @PostMapping("/_query")
    @QueryAction
    public Mono<List<PatrolMobileService.MobileTask>> query(@RequestBody @Valid Request request) {
        return mobile.tasks(request.date(), !Boolean.FALSE.equals(request.mine()), request.nfcCode());
    }
}
