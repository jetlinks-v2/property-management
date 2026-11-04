package org.jetlinks.pro.property.base.service;

import static org.jetlinks.pro.property.base.service.ArchiveScope.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.entity.*;
import org.jetlinks.pro.property.base.organization.OrganizationType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/** 组织配置页与档案页共用的空间读取及根绑定入口，空间结构仍由 SaaS 管理。 */
@Service
@RequiredArgsConstructor
public class PropertySpaceService {
    private final ArchiveScope scope;
    private final ReactiveRepository<PropertySpaceEntity, String> spaces;
    private final ReactiveRepository<PropertySpaceBindingEntity, String> bindings;
    private final ReactiveRepository<PropertyOrganizationEntity, String> organizations;
    private final ReactiveRepository<PropertyHouseEntity, String> houses;

    public Mono<List<Project>> projects() {
        return scope.authenticated()
                .thenMany(
                        AssetsHolder.filterAssets(
                                organizations
                                        .createQuery()
                                        .where("deleted", false)
                                        .fetch(),
                                ArchiveScope.ORGANIZATION,
                                PropertyOrganizationEntity::getId,
                                CrudAssetPermission.read))
                .collectList()
                .map(PropertySpaceService::projectOptions);
    }

    /** 路径只使用权限过滤后的组织；隐藏的祖先不能通过项目选择器泄漏。 */
    private static List<Project> projectOptions(List<PropertyOrganizationEntity> visible) {
        var byId = new HashMap<String, PropertyOrganizationEntity>();
        visible.forEach(node -> byId.put(node.getId(), node));
        return visible.stream()
                .filter(node -> node.getOrganizationType() == OrganizationType.project)
                .sorted(
                        Comparator.comparing(
                                        PropertyOrganizationEntity::getSortIndex,
                                        Comparator.nullsLast(Long::compareTo))
                                .thenComparing(PropertyOrganizationEntity::getId))
                .map(
                        node -> {
                            var path = new LinkedList<OrganizationPath>();
                            var visited = new HashSet<String>();
                            for (var current = node;
                                    current != null && visited.add(current.getId());
                                    current = byId.get(current.getParentId())) {
                                path.addFirst(
                                        new OrganizationPath(current.getId(), current.getName()));
                            }
                            return new Project(node.getId(), node.getName(), List.copyOf(path));
                        })
                .toList();
    }

    public Mono<List<SpaceView>> roots() {
        return scope.authenticated()
                .thenMany(
                        AssetsHolder.filterAssets(
                                spaces.createQuery()
                                        .where("deleted", false)
                                        .isNull("parentId")
                                        .fetch(),
                                ArchiveScope.SPACE,
                                PropertySpaceEntity::getId,
                                CrudAssetPermission.read))
                .concatMap(node -> binding(node.getId()).map(binding -> view(node, binding)))
                .collectList();
    }

    public Mono<List<SpaceView>> tree(String projectId) {
        return scope.spaces(projectId, null, CrudAssetPermission.read)
                .map(
                        all -> {
                            var byId = new LinkedHashMap<String, SpaceView>();
                            all.stream()
                                    .sorted(
                                            Comparator.comparing(
                                                            PropertySpaceEntity::getSortIndex,
                                                            Comparator.nullsLast(Long::compareTo))
                                                    .thenComparing(PropertySpaceEntity::getId))
                                    .forEach(
                                            e -> {
                                                var view = view(e, null);
                                                view.setOrganizationId(projectId);
                                                byId.put(e.getId(), view);
                                            });
                            var result = new ArrayList<SpaceView>();
                            byId.values()
                                    .forEach(
                                            e -> {
                                                var parent = byId.get(e.getParentId());
                                                if (parent == null) result.add(e);
                                                else parent.getChildren().add(e);
                                            });
                            return result;
                        });
    }

    /** 组织配置中的只读预览，同时校验根空间和已关联组织的读取权限。 */
    public Mono<List<SpaceView>> rootTree(String rootId) {
        return scope.authenticated()
                .then(
                        AssetsHolder.assertPermission(
                                ArchiveScope.SPACE, rootId, CrudAssetPermission.read))
                .then(spaces.findById(rootId))
                .filter(node -> !node.getDeleted() && node.getParentId() == null)
                .switchIfEmpty(Mono.error(error("space_not_root", 409)))
                .then(binding(rootId))
                .flatMap(
                        current -> {
                            Mono<Void> permission =
                                    current.getOrganizationId() == null
                                            ? Mono.empty()
                                            : AssetsHolder.assertPermission(
                                                    ArchiveScope.ORGANIZATION,
                                                    current.getOrganizationId(),
                                                    CrudAssetPermission.read);
                            return permission
                                    .thenMany(
                                            AssetsHolder.filterAssets(
                                                    spaces.createQuery()
                                                            .where("rootId", rootId)
                                                            .and("deleted", false)
                                                            .fetch(),
                                                    ArchiveScope.SPACE,
                                                    PropertySpaceEntity::getId,
                                                    CrudAssetPermission.read))
                                    .take(20001)
                                    .collectList()
                                    .map(nodes -> previewTree(nodes, current));
                        });
    }

    private static List<SpaceView> previewTree(
            List<PropertySpaceEntity> nodes, PropertySpaceBindingEntity binding) {
        if (nodes.size() > 20000) throw error("too_many", 400);
        var order =
                Comparator.comparing(
                                PropertySpaceEntity::getSortIndex,
                                Comparator.nullsLast(Long::compareTo))
                        .thenComparing(PropertySpaceEntity::getId);
        var byId = new LinkedHashMap<String, SpaceView>();
        nodes.stream().sorted(order).forEach(node -> byId.put(node.getId(), view(node, binding)));
        var result = new ArrayList<SpaceView>();
        byId.values()
                .forEach(
                        node -> {
                            var parent = byId.get(node.getParentId());
                            if (parent == null) result.add(node);
                            else parent.getChildren().add(node);
                        });
        return result;
    }

    @Transactional
    public Mono<SpaceView> bind(String rootId, String organizationId, long version) {
        if (version < 0) return Mono.error(error("version_required", 400));
        return scope.authenticated()
                .flatMap(
                        auth ->
                                AssetsHolder.assertPermission(
                                                ArchiveScope.SPACE,
                                                rootId,
                                                CrudAssetPermission.save)
                                        .then(binding(rootId))
                                        .flatMap(
                                                old ->
                                                        lockOrganizations(
                                                                old.getOrganizationId(),
                                                                organizationId))
                                        .then(lockRoot(rootId))
                                        .then(binding(rootId))
                                        .flatMap(
                                                current ->
                                                        applyBinding(
                                                                auth,
                                                                rootId,
                                                                organizationId,
                                                                version,
                                                                current)));
    }

    private Mono<Void> lockOrganizations(String previous, String next) {
        var ids = new TreeSet<String>();
        if (previous != null) ids.add(previous);
        if (next != null) ids.add(next);
        // 按稳定顺序锁定变更前后组织，避免两个根交换组织时出现相反的加锁顺序。
        return Flux.fromIterable(ids)
                .concatMap(
                        id ->
                                AssetsHolder.assertPermission(
                                                ArchiveScope.ORGANIZATION,
                                                id,
                                                CrudAssetPermission.save)
                                        .then(scope.lock(id)))
                .then();
    }

    private Mono<SpaceView> applyBinding(
            org.hswebframework.web.authorization.Authentication auth,
            String rootId,
            String organizationId,
            long version,
            PropertySpaceBindingEntity current) {
        if (current.getVersion() != version) return Mono.error(error("version_conflict", 409));
        Mono<Void> organization =
                organizationId == null
                        ? Mono.empty()
                        : organizations
                                .findById(organizationId)
                                .filter(o -> !o.getDeleted() && o.getOrganizationType() != null)
                                .switchIfEmpty(Mono.error(error("organization_untyped", 409)))
                                .then();
        Mono<Void> guard =
                Objects.equals(current.getOrganizationId(), organizationId)
                        ? Mono.empty()
                        : houses.createQuery()
                                .where("rootId", rootId)
                                .count()
                                .flatMap(
                                        count ->
                                                count == 0
                                                        ? Mono.empty()
                                                        : Mono.error(error("binding_in_use", 409)));
        return organization
                .then(assertSingleBinding(rootId, organizationId))
                .then(guard)
                .then(Mono.defer(() -> persistBinding(auth, rootId, organizationId, version)));
    }

    private Mono<Void> assertSingleBinding(String rootId, String organizationId) {
        if (organizationId == null) return Mono.empty();
        // bind 已持有目标组织行锁；不同根并发关联同一组织也必须串行检查。
        // 不使用调用者的数据权限过滤，避免不可见的既有关联被漏检。
        return bindings.createQuery()
                .where("organizationId", organizationId)
                .not("id", rootId)
                .count()
                .flatMap(
                        count ->
                                count == 0
                                        ? Mono.empty()
                                        : Mono.error(error("organization_already_bound", 409)));
    }

    private Mono<SpaceView> persistBinding(
            org.hswebframework.web.authorization.Authentication auth,
            String rootId,
            String organizationId,
            long version) {
        var saved = new PropertySpaceBindingEntity();
        saved.setId(rootId);
        saved.setOrganizationId(organizationId);
        PropertyDictionaryService.init(saved, auth);
        saved.setVersion(version + 1);
        return bindings.findById(rootId)
                .hasElement()
                .flatMap(
                        exists ->
                                exists
                                        ? updateBinding(auth, rootId, organizationId, version)
                                        : bindings.insert(saved).then())
                .then(spaces.findById(rootId))
                .map(node -> view(node, saved));
    }

    private Mono<Void> updateBinding(
            org.hswebframework.web.authorization.Authentication auth,
            String rootId,
            String organizationId,
            long version) {
        var update = PropertyDictionaryService.audit(bindings.createUpdate(), auth, version);
        if (organizationId == null) update.setNull("organizationId");
        else update.set("organizationId", organizationId);
        return update.where("id", rootId)
                .and("version", version)
                .execute()
                .flatMap(
                        changed ->
                                changed == 1
                                        ? Mono.empty()
                                        : Mono.error(error("version_conflict", 409)));
    }

    private Mono<PropertySpaceBindingEntity> binding(String id) {
        return bindings.findById(id).defaultIfEmpty(emptyBinding(id));
    }

    private Mono<PropertySpaceEntity> lockRoot(String id) {
        var query = new org.hswebframework.web.api.crud.entity.QueryParamEntity();
        query.setForUpdate(true);
        return spaces.createQuery()
                .setParam(query)
                .where("id", id)
                .and("deleted", false)
                .isNull("parentId")
                .fetchOne()
                .switchIfEmpty(Mono.error(error("space_not_root", 409)));
    }

    private static PropertySpaceBindingEntity emptyBinding(String id) {
        var result = new PropertySpaceBindingEntity();
        result.setId(id);
        result.setVersion(0L);
        result.setDeleted(false);
        return result;
    }

    private static SpaceView view(PropertySpaceEntity node, PropertySpaceBindingEntity binding) {
        var result = new SpaceView();
        result.setId(node.getId());
        result.setName(node.getName());
        result.setParentId(node.getParentId());
        result.setRootId(node.getRootId());
        result.setFullName(node.getFullName());
        result.setSpaceTypeId(node.getSpaceTypeId());
        result.setSpaceTypeName(node.getSpaceTypeName());
        result.setSpaceLocation(node.getSpaceLocation());
        result.setSortIndex(node.getSortIndex());
        result.setLeaf(node.getLeaf());
        result.setVersion(binding == null ? 0L : binding.getVersion());
        result.setOrganizationId(binding == null ? null : binding.getOrganizationId());
        return result;
    }

    public record OrganizationPath(String id, String name) {}

    public record Project(String id, String name, List<OrganizationPath> organizationPath) {}

    @Getter
    @Setter
    public static class SpaceView {
        private String id, name, parentId, rootId, fullName, organizationId;
        private String spaceTypeId, spaceTypeName, spaceLocation;
        private Long sortIndex, version;
        private Boolean leaf;
        private List<SpaceView> children = new ArrayList<>();
    }
}
