package org.jetlinks.pro.smartpark.repair.service;

import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.DefaultDimensionType;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleDimension;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.hswebframework.web.authorization.simple.SimplePermission;
import org.jetlinks.pro.assets.scope.*;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 scope resolver 验证来源 scope 冲突、目标成员资格及角色裁剪，避免替身绕过授权。 */
class QualityRepairTargetScopeTest {
 private SimpleAuthentication user(boolean targetMember){
  var auth=new SimpleAuthentication();
  auth.setUser(new SimpleUser("operator","operator","Operator","user",Map.of()));
  var dimensions=new ArrayList<org.hswebframework.web.authorization.Dimension>();
  dimensions.add(SimpleDimension.of("quality","Quality",BusinessApplicationDimension.INSTANCE,Map.of()));
  dimensions.add(role("quality-role","quality"));
  if(targetMember){dimensions.add(SimpleDimension.of("repair","Repair",BusinessApplicationDimension.INSTANCE,Map.of()));dimensions.add(role("repair-role","repair"));}
  var quality=new SimplePermission();quality.setId("quality-work-order");quality.setActions(Set.of("transferRepair"));
  quality.setOptions(Map.of("sources",Map.of(BusinessApplicationDimension.TYPE,Map.of("quality",List.of("transferRepair")))));
  var repair=new SimplePermission();repair.setId("repair-order");repair.setActions(Set.of("create"));
  repair.setOptions(Map.of("sources",Map.of(BusinessApplicationDimension.TYPE,Map.of("repair",List.of("create")))));
  auth.setDimensions(dimensions);auth.setPermissions(List.of(quality,repair));return auth;
 }
 private SimpleDimension role(String id,String app){return SimpleDimension.of(id,id,DefaultDimensionType.role,Map.of(AssetScopeSources.OPTION_KEY,Map.of(BusinessApplicationDimension.TYPE,List.of(app))));}
 private AssetScopeContext source(){return AssetScopeContext.of(AssetScope.of(BusinessApplicationDimension.TYPE,"quality"));}
 @Test void directMergeOfTwoApplicationsIsDenied(){
  StepVerifier.create(new AssetScopeResolverManager().resolve(AssetScope.of(BusinessApplicationDimension.TYPE,"repair"),user(true)).contextWrite(AssetScopeContext.replace(source())))
   .expectError(AccessDenyException.class).verify();
 }
 @Test void targetActivationKeepsActorAndCropsSourceActions(){
  StepVerifier.create(QualityRepairCommandSupport.activateTarget(new AssetScopeResolverManager(),user(true),"repair").contextWrite(AssetScopeContext.replace(source())))
   .assertNext(active->{
    assertEquals("repair",active.context().getScope(BusinessApplicationDimension.TYPE).orElseThrow().id());
    Authentication auth=active.authentication();assertEquals("operator",auth.getUser().getId());
    assertTrue(auth.getDimension(DefaultDimensionType.role,"repair-role").isPresent());
    assertTrue(auth.hasPermission("repair-order","create"));
    assertFalse(auth.hasPermission("quality-work-order","transferRepair"));
   }).verifyComplete();
 }
 @Test void sourceMembershipDoesNotGrantTargetMembership(){
  StepVerifier.create(QualityRepairCommandSupport.activateTarget(new AssetScopeResolverManager(),user(false),"repair").contextWrite(AssetScopeContext.replace(source())))
   .expectError(AccessDenyException.class).verify();
 }
}
