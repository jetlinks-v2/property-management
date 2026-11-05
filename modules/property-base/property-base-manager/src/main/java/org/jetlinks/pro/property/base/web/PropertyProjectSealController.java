package org.jetlinks.pro.property.base.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.ResourceAction;
import org.jetlinks.pro.property.base.entity.PropertyOrganizationEntity;
import org.jetlinks.pro.property.base.service.PropertyProjectSealService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 专用项目章维护动作，不开放组织通用编辑或文件物理删除能力。 */
@RestController
@RequestMapping("/property/organization")
@Resource(id = "property-organization", name = "物业组织类型配置")
@Authorize
@RequiredArgsConstructor
@Tag(name = "物业组织类型配置")
public class PropertyProjectSealController {
    private final PropertyProjectSealService seals;

    @PutMapping("/{id}/seal")
    @ResourceAction(id = "seal", name = "维护项目章")
    @Operation(summary = "保存或清除项目章文件引用")
    public Mono<PropertyOrganizationEntity> updateSeal(
            @PathVariable String id, @Valid @RequestBody SealUpdate request) {
        return seals.update(id, request.fileId(), request.version());
    }

    /** 必须显式提供 fileId，null 表示清除；版本来自最新组织详情。 */
    public record SealUpdate(
            @JsonProperty(required = true) @Size(min = 1, max = 64) String fileId,
            @NotNull @Min(0) Long version) {}
}
