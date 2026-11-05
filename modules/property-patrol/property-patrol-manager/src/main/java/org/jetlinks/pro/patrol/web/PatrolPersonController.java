package org.jetlinks.pro.patrol.web;

import lombok.AllArgsConstructor;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.jetlinks.pro.patrol.integration.SaasPersonDirectory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

/** 岗位及排班人员选择器；不创建第二套 SaaS 账号。 */
@RestController
@AllArgsConstructor
@RequestMapping("/park/patrol/persons")
@Resource(id = "patrol-point", name = "巡更点位")
public class PatrolPersonController {
    private final SaasPersonDirectory people;

    @PostMapping("/_query")
    @QueryAction
    public Mono<PagerResult<SaasPersonDirectory.Person>> query(
            @RequestBody QueryParamEntity request) {
        return people.query(request);
    }
}
