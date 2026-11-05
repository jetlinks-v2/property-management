package org.jetlinks.pro.patrol.web;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.jetlinks.pro.patrol.entity.PatrolRole;
import org.jetlinks.pro.patrol.service.PatrolRoleService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 巡更只读角色候选；仅当前应用角色及可读成员，不开放角色或成员维护入口。 */
@RestController
@Authorize
@RequiredArgsConstructor
@RequestMapping("/park/patrol/roles")
public class PatrolRoleController {
    private final PatrolRoleService roles;

    @PostMapping("/_query")
    public Mono<PagerResult<PatrolRole>> query(@RequestBody QueryParamEntity query) {
        return roles.queryPager(query);
    }
}
