package org.jetlinks.pro.smartpark.handover.asset;

import lombok.Getter;

import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;

import java.util.Arrays;
import java.util.List;

/** 查验问题独立资产；项目与外协权限由关联投影统一计算。 */
@Getter
public enum HandoverAssetType implements EnumAssetType {
    handoverProblem;

    public String getId() {
        return name();
    }

    public String getName() {
        return "承接查验问题";
    }

    public List<AssetPermission> getPermissions() {
        return Arrays.asList(CrudAssetPermission.values());
    }

    public List<String> getResourceId() {
        return List.of("handover-problem");
    }
}
