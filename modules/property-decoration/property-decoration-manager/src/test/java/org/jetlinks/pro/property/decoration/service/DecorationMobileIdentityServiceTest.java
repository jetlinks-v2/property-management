package org.jetlinks.pro.property.decoration.service;

import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.pro.property.base.archive.PropertyOwnerHouse;
import org.jetlinks.pro.property.base.user.PropertyEmployeeInfo;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class DecorationMobileIdentityServiceTest {
    @Test void singleAndDualIdentitiesRequireBothApplicationPermissionAndRealArchive(){
        var scope=mock(DecorationScope.class);var auth=mock(Authentication.class);
        when(scope.current()).thenReturn(Mono.just(new DecorationScope.Context("tenant","app","person","业主员工",auth)));
        when(scope.ownerHouses()).thenReturn(Mono.just(List.of(mock(PropertyOwnerHouse.class))));
        when(scope.employee()).thenReturn(Mono.just(new PropertyEmployeeInfo("person","员工","",List.of())));
        var service=new DecorationMobileIdentityService(scope);
        when(auth.hasPermission("decoration-owner","query")).thenReturn(true);
        assertEquals(new DecorationMobileIdentityService.Identity(true,false),service.current().block());
        when(auth.hasPermission("decoration-task","query")).thenReturn(true);
        assertEquals(new DecorationMobileIdentityService.Identity(true,true),service.current().block());
        when(scope.ownerHouses()).thenReturn(Mono.just(List.of()));
        assertEquals(new DecorationMobileIdentityService.Identity(false,true),service.current().block());
        when(scope.employee()).thenReturn(Mono.error(DecorationRules.error("employee_required",403)));
        assertEquals(new DecorationMobileIdentityService.Identity(false,false),service.current().block());
        when(scope.ownerHouses()).thenReturn(Mono.error(DecorationRules.error("directory_unavailable",503)));
        StepVerifier.create(service.current()).expectErrorMatches(error->error.getMessage().contains("directory_unavailable")).verify();
    }
}
