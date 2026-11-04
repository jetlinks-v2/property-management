package org.jetlinks.pro.patrol.web;

import lombok.AllArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.jetlinks.pro.patrol.entity.PatrolExecutionEntity;
import org.jetlinks.pro.patrol.service.PatrolMobileService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@AllArgsConstructor
@RequestMapping("/park/patrol/mobile/history")
@Resource(id = "patrol-execution", name = "巡更执行记录")
public class PatrolMobileHistoryController {
    private final PatrolMobileService mobile;
    @PostMapping("/_query")
    @QueryAction
    public Mono<PagerResult<PatrolExecutionEntity>> query(@RequestBody QueryParamEntity request) {
        return mobile.history(request);
    }
}
