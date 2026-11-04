package org.jetlinks.pro.patrol.integration;

import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.base.organization.OrganizationType;
import java.util.List;

public final class ProjectTestContexts {
    public static PatrolProjectContext project(String scope) {
        var node = new PropertyOrganizationInfo(); node.setId("project"); node.setName("Project");
        node.setOrganizationType(OrganizationType.project); node.setChildren(List.of());
        return new PatrolProjectContext(List.of(node), "project", scope);
    }
}
