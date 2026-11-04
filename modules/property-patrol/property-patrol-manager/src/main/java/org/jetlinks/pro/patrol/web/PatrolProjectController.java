package org.jetlinks.pro.patrol.web;

import org.hswebframework.web.authorization.annotation.Authorize;
import org.jetlinks.pro.patrol.integration.PatrolProjectContext;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import java.util.List;

/** 已过滤组织树只读投影；仅供项目选择，不要求组织维护权限，不返回不可见祖先。 */
@RestController
@Authorize
@RequestMapping("/park/patrol/projects")
public class PatrolProjectController {
    @GetMapping("/_tree")
    public Mono<List<PropertyOrganizationInfo>> tree() {
        return PatrolProjectContext.current().map(PatrolProjectContext::tree);
    }
}
