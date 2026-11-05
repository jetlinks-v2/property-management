package org.jetlinks.pro.smartpark.handover.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.base.space.PropertySpaceInfo;
import org.jetlinks.pro.property.base.user.PropertyEmployeeInfo;
import org.jetlinks.pro.smartpark.handover.service.HandoverDirectory;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/** Authorized public directories; no sync, source mutation or fallback mirror is introduced. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/property/handover/directory")
@Resource(id = "handover-directory", name = "承接查验目录")
public class HandoverDirectoryController {
    private final HandoverDirectory directory;

    @GetMapping("/projects")
    @QueryAction
    public Flux<PropertyOrganizationInfo> projects() {
        return directory.projects();
    }

    @GetMapping("/spaces")
    @QueryAction
    public Flux<PropertySpaceInfo> spaces(@RequestParam String projectId) {
        return directory.spaces(projectId);
    }

    @GetMapping("/organizations")
    @QueryAction
    public Flux<PropertyOrganizationInfo> organizations(@RequestParam String projectId) {
        return directory.organizations(projectId);
    }

    @GetMapping("/users")
    @QueryAction
    public Mono<PagerResult<PropertyEmployeeInfo>> users(
            @RequestParam String projectId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int pageIndex,
            @RequestParam(defaultValue = "12") int pageSize) {
        return directory.users(projectId, keyword, pageIndex, pageSize);
    }

    @GetMapping("/me")
    @QueryAction
    public Mono<Map<String, Object>> me() {
        return directory.me();
    }
}
