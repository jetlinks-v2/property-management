package org.jetlinks.pro.patrol.integration;

import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.base.organization.OrganizationType;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单次请求使用基础模块已经过滤的树；只识别类型和路径，不再计算组织授权或查询不可见祖先。
 * selectedProject 只能缩小树内项目；树为空意味着没有项目，不能退回全库。
 */
public final class PatrolProjectContext {
    public static final String HEADER = "X-Patrol-Project";
    private final List<PropertyOrganizationInfo> tree;
    private final Map<String, PropertyOrganizationInfo> nodes = new LinkedHashMap<>();
    private final Map<String, Set<String>> paths = new LinkedHashMap<>();
    private final String selectedProject;
    private final String scopeKey;
    private String attachmentOwner;

    public PatrolProjectContext(List<PropertyOrganizationInfo> tree, String selectedProject, String scopeKey) {
        this.tree = List.copyOf(tree);
        index(tree, Set.of());
        this.selectedProject = selectedProject == null || selectedProject.isBlank() ? null : selectedProject;
        this.scopeKey = scopeKey;
        if (this.selectedProject != null) requireProject(this.selectedProject);
    }

    private void index(List<PropertyOrganizationInfo> list, Set<String> ancestors) {
        for (var node : list) {
            var path = new LinkedHashSet<>(ancestors);
            path.add(node.getId());
            nodes.put(node.getId(), node);
            paths.put(node.getId(), Set.copyOf(path));
            index(node.getChildren() == null ? List.of() : node.getChildren(), path);
        }
    }

    public static Mono<PatrolProjectContext> current() {
        return Mono.deferContextual(ctx -> Mono.justOrEmpty(ctx.<PatrolProjectContext>getOrEmpty(PatrolProjectContext.class)))
                .switchIfEmpty(Mono.error(new AccessDenyException.NoStackTrace()));
    }

    public List<PropertyOrganizationInfo> tree() { return tree; }
    public String scopeKey() { return scopeKey; }
    public void attachmentOwner(String owner) {
        if (owner != null && !owner.isBlank()) attachmentOwner = requireOrganization(owner);
    }
    public String attachmentOwner() { return attachmentOwner; }
    public String selectedProject() { return selectedProject; }
    public Set<String> projects() {
        if (selectedProject != null) return Set.of(selectedProject);
        var result = new LinkedHashSet<String>();
        nodes.values().stream().filter(node -> node.getOrganizationType() == OrganizationType.project)
                .forEach(node -> result.add(node.getId()));
        return Set.copyOf(result);
    }

    public Set<String> organizations() {
        return selectedProject == null ? Set.copyOf(nodes.keySet()) : paths.get(selectedProject);
    }

    public Set<String> employeeOrganizations() {
        var result = new LinkedHashSet<>(organizations());
        if (selectedProject != null) collect(nodes.get(selectedProject), result);
        return Set.copyOf(result);
    }
    private void collect(PropertyOrganizationInfo node, Set<String> ids) {
        ids.add(node.getId());
        for (var child : node.getChildren() == null ? List.<PropertyOrganizationInfo>of() : node.getChildren()) collect(child, ids);
    }

    public String requireProject(String id) {
        var node = nodes.get(id);
        if (node == null || node.getOrganizationType() != OrganizationType.project
                || selectedProject != null && !selectedProject.equals(id))
            throw new AccessDenyException.NoStackTrace();
        return id;
    }

    public String projectForWrite(String requested) {
        String id = requested == null || requested.isBlank() ? selectedProject : requested;
        if (id == null) throw new BusinessException("error.patrol.project_required", 400);
        return requireProject(id);
    }

    public String requireOrganization(String id) {
        if (id == null || id.isBlank()) throw new BusinessException("error.patrol.owner_required", 400);
        if (!organizations().contains(id)) throw new AccessDenyException.NoStackTrace();
        return id;
    }

    public PatrolProjectContext forProject(String id) {
        requireProject(id);
        return new PatrolProjectContext(tree, id, scopeKey);
    }

    public boolean availableFor(String project, String referenceProject, String owner) {
        return project != null && (project.equals(referenceProject)
                || referenceProject == null && owner != null && paths.getOrDefault(project, Set.of()).contains(owner));
    }

    /** 引用可属于同一项目，或使用本节点可见路径上的组织共享配置；共享配置不能反向引用项目配置。 */
    public boolean canReference(String project, String owner, String referenceProject, String referenceOwner) {
        if (project != null) return availableFor(project, referenceProject, referenceOwner);
        return owner != null && referenceProject == null && referenceOwner != null
                && paths.getOrDefault(owner, Set.of()).contains(referenceOwner);
    }

    public String projectName(String id) { return nodes.get(requireProject(id)).getName(); }

    /** 仅用于共享模板的项目适用性，基于本次已经可见的路径，不拓宽组织树。 */
    public boolean canUse(String projectId, String ownerOrganizationId) {
        return projectId != null && projects().contains(projectId)
                || projectId == null && ownerOrganizationId != null && organizations().contains(ownerOrganizationId);
    }
}
