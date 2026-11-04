package org.jetlinks.pro.smartpark.repair.service;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.entity.PropertyOrganizationEntity;
import org.jetlinks.pro.property.entity.PropertySpaceEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepairQrScopeTest {
    @Test void classificationMustBeEnabledCompleteThirdLevelUnderPublishedNode() {
        var grant=grant();
        assertTrue(grant.categoryAllowed("leaf"));
        assertFalse(grant.categoryAllowed("second"));assertFalse(grant.categoryAllowed("other-leaf"));
        grant.configurations.remove("second");assertFalse(grant.categoryAllowed("leaf"));
    }
    @Test void spaceScopeUsesTreeAndDoesNotTrustLookalikePathOrSibling() {
        var grant=grant();
        assertTrue(grant.spaceAllowed("room"));assertTrue(grant.selectable("room"));
        assertFalse(grant.selectable("building"));assertFalse(grant.spaceAllowed("other-room"));
        assertFalse(grant.spaceAllowed("unknown"));
        grant.spaces.get("building").setParentId("room");
        assertFalse(grant.within("room",List.of("unrelated-root")),"循环空间不能扩张授权");
    }
    @Test void roomSwitchAndPublicAreaHaveDistinctSelectionSemantics() {
        var grant=grant();grant.settings.put("enableRoom",false);
        assertTrue(grant.selectable("building"));assertFalse(grant.selectable("room"));
        grant.settings.put("publicAreaRootIds",List.of("building"));
        assertEquals(1,grant.positionType("room"));assertTrue(grant.selectable("room"));
    }
    @Test void areaSelectionNarrowsPublishedTree() {
        var grant=grant();grant.settings.put("enableCampus",true);
        grant.settings.put("campusOptions",List.of(Map.of("name","一层","spaceRootIds",List.of("room"))));
        assertTrue(grant.spaceAllowed("room"));assertFalse(grant.spaceAllowed("building"));
    }
    @Test void publicProjectionOmitsFlowRolesAndOtherProjectDirectory() {
        var provider=mock(RepairQrGrantProvider.class);var grant=grant();when(provider.resolve("published-code")).thenReturn(Mono.just(grant));
        grant.configurations.get("leaf").setConfiguration(Map.of("followRoleId","private-role","auditRoleId","private-audit"));
        var options=org.mockito.Mockito.mock(RepairFormOptionsService.class);
        org.mockito.Mockito.when(options.load(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(reactor.core.publisher.Mono.just(RepairFormOptions.from(Map.of())));
        var service=new RepairPublicService(provider,null,null,null,options);
        StepVerifier.create(service.form("published-code")).assertNext(view->{
            String json=com.alibaba.fastjson.JSON.toJSONString(view);
            assertFalse(json.contains("private-role"));assertFalse(json.contains("tenant-a"));
            assertFalse(json.contains("other-room"));assertFalse(json.contains("other-leaf"));
            assertEquals("欢迎报事",view.get("welcomeText"));
        }).verifyComplete();
    }
    @Test void invalidAndMissingConfigurationFieldsCannotBePublished() {
        var qr=grant().qr;RepairQrRules.validate(qr);assertEquals("published-code",qr.getQrCode());
        qr.getConfiguration().put("code","../other-project");assertThrows(BusinessException.class,()->RepairQrRules.validate(qr));
        qr.getConfiguration().put("code","valid");qr.getConfiguration().put("spaceRootIds",List.of());
        assertThrows(BusinessException.class,()->RepairQrRules.validate(qr));
    }
    private static RepairQrGrantProvider.Grant grant() {
        var qr=new RepairConfigurationEntity();qr.setId("qr-id");qr.setName("楼栋二维码");qr.setKind("qr");qr.setApplicationId("application-a");
        qr.setConfiguration(new HashMap<>(Map.of("code","published-code","welcomeText","欢迎报事","channelId","channel",
            "categoryIds",List.of("root"),"spaceRootIds",List.of("building"),"enableRoom",true)));
        var project=new PropertyOrganizationEntity();project.setId("project-a");project.setTenantId("tenant-a");project.setSourceName("测试小区");
        return new RepairQrGrantProvider.Grant(qr,project,List.of(
            config("root","category",null),config("second","category","root"),config("leaf","category","second"),
            config("other-leaf","category",null),config("label","label","leaf"),config("description","description","label"),config("channel","channel",null)),
            List.of(space("building",null),space("room","building"),space("other-room",null)));
    }
    private static RepairConfigurationEntity config(String id,String kind,String parent) {
        var row=new RepairConfigurationEntity();row.setId(id);row.setName(id);row.setKind(kind);row.setParentId(parent);return row;
    }
    private static PropertySpaceEntity space(String id,String parent) {
        var row=new PropertySpaceEntity();row.setId(id);row.setSourceName(id);row.setParentId(parent);return row;
    }
}
