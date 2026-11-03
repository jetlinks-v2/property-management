package org.jetlinks.pro.property.base.service;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.base.entity.PropertySpaceEntity;
import org.jetlinks.pro.property.base.space.SaasSpaceInfo;

import java.util.*;

/** 写入前验证完整空间森林，所有派生字段均根据全量来源计算，不受用户权限裁剪影响。 */
final class SpaceSnapshot {
    static final int MAX_NODES = 20_000;
    private static final int MAX_DEPTH = 128;

    private SpaceSnapshot() {}

    static Map<String, PropertySpaceEntity> from(List<SaasSpaceInfo> sources) {
        if (sources == null || sources.size() > MAX_NODES) throw invalid();
        var result = new LinkedHashMap<String, PropertySpaceEntity>();
        var parents = new HashSet<String>();
        for (var source : sources) {
            validate(source);
            var entity = new PropertySpaceEntity();
            entity.setId(source.getId());
            entity.setName(source.getName());
            entity.setParentId(
                    source.getParentId() == null || source.getParentId().isBlank()
                            ? null
                            : source.getParentId());
            entity.setSortIndex(source.getSortIndex());
            entity.setSpaceTypeId(source.getSpaceTypeId());
            entity.setSpaceTypeName(source.getSpaceTypeName());
            if (result.putIfAbsent(entity.getId(), entity) != null) throw invalid();
            if (entity.getParentId() != null) parents.add(entity.getParentId());
        }
        for (var entity : result.values()) {
            var seen = new HashSet<String>();
            var path = new ArrayDeque<String>();
            var location = new ArrayDeque<String>();
            var current = entity;
            while (current != null) {
                if (!seen.add(current.getId()) || seen.size() > MAX_DEPTH) throw invalid();
                path.addFirst(current.getName());
                String type =
                        current.getSpaceTypeName() != null
                                ? current.getSpaceTypeName()
                                : current.getSpaceTypeId();
                location.addFirst(
                        type == null ? current.getName() : type + "：" + current.getName());
                entity.setRootId(current.getId());
                String parent = current.getParentId();
                if (parent != null && !result.containsKey(parent)) throw invalid();
                current = result.get(parent);
            }
            String fullName = String.join("/", path);
            if (fullName.length() > 4096) throw invalid();
            entity.setFullName(fullName);
            entity.setSpaceLocation(String.join(" / ", location));
            // 单独一个根也是末端；不能按权限过滤后的 children 推断房屋资格。
            entity.setLeaf(!parents.contains(entity.getId()));
            entity.setDeleted(false);
        }
        return result;
    }

    static void validate(SaasSpaceInfo source) {
        if (source == null
                || !id(source.getId())
                || source.getName() == null
                || source.getName().isBlank()
                || source.getName().length() > 128
                || source.getSpaceTypeId() != null && !id(source.getSpaceTypeId())
                || source.getSpaceTypeName() != null && source.getSpaceTypeName().length() > 128
                || source.getParentId() != null
                        && !source.getParentId().isBlank()
                        && !id(source.getParentId())) throw invalid();
    }

    private static boolean id(String value) {
        return value != null && value.matches("[0-9a-zA-Z_-]{1,64}");
    }

    static boolean changed(PropertySpaceEntity before, PropertySpaceEntity after) {
        return Boolean.TRUE.equals(before.getDeleted())
                || !Objects.equals(before.getName(), after.getName())
                || !Objects.equals(before.getParentId(), after.getParentId())
                || !Objects.equals(before.getRootId(), after.getRootId())
                || !Objects.equals(before.getFullName(), after.getFullName())
                || !Objects.equals(before.getSpaceTypeId(), after.getSpaceTypeId())
                || !Objects.equals(before.getSpaceTypeName(), after.getSpaceTypeName())
                || !Objects.equals(before.getSpaceLocation(), after.getSpaceLocation())
                || !Objects.equals(before.getSortIndex(), after.getSortIndex())
                || !Objects.equals(before.getLeaf(), after.getLeaf());
    }

    static BusinessException invalid() {
        return new BusinessException.NoStackTrace("error.property_space_snapshot_invalid", 502);
    }
}
