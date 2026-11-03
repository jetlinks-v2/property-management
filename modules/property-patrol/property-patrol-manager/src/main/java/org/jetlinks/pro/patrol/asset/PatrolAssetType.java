package org.jetlinks.pro.patrol.asset;

import lombok.AllArgsConstructor;
import lombok.Getter;

import org.jetlinks.pro.assets.AssetPermission;
import org.jetlinks.pro.assets.EnumAssetType;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;

import java.util.Arrays;
import java.util.List;

/** 配置独立授权；任务和执行记录关联稳定计划资产，历史不依赖点位是否仍启用。 */
@Getter
@AllArgsConstructor
public enum PatrolAssetType implements EnumAssetType {
    patrolCategory("巡更分类", List.of("patrol-category")),
    patrolPoint("巡更点位", List.of("patrol-point")),
    patrolStandard("巡更标准", List.of("patrol-standard")),
    patrolPost("巡更岗位", List.of("patrol-post")),
    patrolRoster("巡更排班", List.of("patrol-roster")),
    patrolPlan("巡更计划", List.of("patrol-plan", "patrol-task", "patrol-execution", "patrol-report"));
    private final String name;
    private final List<String> resourceId;

    public String getId() {
        return name();
    }

    public List<AssetPermission> getPermissions() {
        return Arrays.asList(CrudAssetPermission.values());
    }
}
