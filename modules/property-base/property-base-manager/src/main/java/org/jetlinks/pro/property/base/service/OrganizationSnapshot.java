package org.jetlinks.pro.property.base.service;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.base.entity.PropertyOrganizationEntity;
import org.jetlinks.sdk.server.auth.OrganizationInfo;

import java.util.*;

/** 在写入前校验完整树；重复、环和缺失父级均拒绝，避免把部分响应视为全量。 */
final class OrganizationSnapshot {
    static final int MAX_NODES = 20_000;

    private OrganizationSnapshot() {}

    static Map<String, PropertyOrganizationEntity> from(List<OrganizationInfo> roots) {
        var result = new LinkedHashMap<String, PropertyOrganizationEntity>();
        for (var root : roots) flatten(root, null, result, 0);
        for (var node : result.values()) {
            var seen = new HashSet<String>();
            var current = node;
            while (current != null) {
                if (!seen.add(current.getId()) || seen.size() > 128) throw invalid();
                String parent = current.getParentId();
                if (parent != null && !result.containsKey(parent)) throw invalid();
                current = result.get(parent);
            }
        }
        return result;
    }

    private static void flatten(
            OrganizationInfo source,
            String enclosingParent,
            Map<String, PropertyOrganizationEntity> result,
            int depth) {
        if (source == null
                || depth > 128
                || result.size() >= MAX_NODES
                || source.getId() == null
                || !source.getId().matches("[0-9a-zA-Z_-]{1,64}")
                || source.getName() == null
                || source.getName().isBlank()
                || source.getName().length() > 64
                || source.getCode() != null && source.getCode().length() > 128) throw invalid();
        String parent =
                source.getParentId() == null || source.getParentId().isBlank()
                        ? null
                        : source.getParentId();
        if (enclosingParent != null && !enclosingParent.equals(parent)) throw invalid();
        var entity = new PropertyOrganizationEntity();
        entity.setId(source.getId());
        entity.setName(source.getName());
        entity.setParentId(parent);
        entity.setCode(source.getCode());
        entity.setSortIndex(source.getSortIndex());
        if (result.putIfAbsent(entity.getId(), entity) != null) throw invalid();
        if (source.getChildren() != null) {
            for (var child : source.getChildren())
                flatten(child, entity.getId(), result, depth + 1);
        }
    }

    static boolean changed(PropertyOrganizationEntity before, PropertyOrganizationEntity after) {
        return Boolean.TRUE.equals(before.getDeleted())
                || !Objects.equals(before.getName(), after.getName())
                || !Objects.equals(before.getCode(), after.getCode())
                || !Objects.equals(before.getParentId(), after.getParentId())
                || !Objects.equals(before.getSortIndex(), after.getSortIndex());
    }

    static BusinessException invalid() {
        return new BusinessException.NoStackTrace("error.property_org_snapshot_invalid", 502);
    }
}
