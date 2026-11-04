package org.jetlinks.pro.property.quality.service;

import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.command.CommandSupportManagerProvider;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 用真实提供者注册表覆盖离线、恢复及拒绝；不把目标授权失败改写成服务离线。 */
class QualityRepairAvailabilityTest {
 private final QualityRepairBridge bridge=new QualityRepairBridge(null,null,null,null);

 @Test void absentProviderIsServiceUnavailable(){
  assertTrue(CommandSupportManagerProvider.supports.get("propertyRepair").isEmpty());
  StepVerifier.create(bridge.support()).expectErrorSatisfies(e->{
   assertInstanceOf(BusinessException.class,e);assertEquals(503,((BusinessException)e).getStatus());
  }).verify();
 }

 @Test void providerRecoveryResolvesSupportOnSubscription(){
  Mono<CommandSupport> lookup=bridge.support();
  CommandSupport service=mock(CommandSupport.class);
  var registration=register(Mono.just(service));
  try{StepVerifier.create(lookup).expectNext(service).verifyComplete();}finally{registration.dispose();}
 }

 @Test void emptyProviderIsServiceUnavailable(){
  var registration=register(Mono.empty());
  try{StepVerifier.create(bridge.support()).expectErrorSatisfies(e->assertEquals(503,((BusinessException)e).getStatus())).verify();}finally{registration.dispose();}
 }

 @Test void targetAuthorizationErrorIsPreserved(){
  var denied=new AccessDenyException();var registration=register(Mono.error(denied));
  try{StepVerifier.create(bridge.support()).expectErrorMatches(e->e==denied).verify();}finally{registration.dispose();}
 }

 @Test void clusterRouteMissingAfterSupportDiscoveryIsUnavailable(){
  for(String code:java.util.List.of("error.unsupported_command_service","error.command.server.not.found")){
   var original=new org.hswebframework.web.exception.I18nSupportException(code,"propertyRepair");
   var mapped=QualityRepairBridge.mapUnavailable(original);
   assertInstanceOf(BusinessException.class,mapped);assertEquals(503,((BusinessException)mapped).getStatus());
   assertSame(original,mapped.getCause());
  }
 }

 @Test void executionAuthorizationAndValidationAreNotMapped(){
  var denied=new AccessDenyException();var invalid=new BusinessException("error.quality.reference_scope",400);
  assertSame(denied,QualityRepairBridge.mapUnavailable(denied));
  assertSame(invalid,QualityRepairBridge.mapUnavailable(invalid));
 }

 private reactor.core.Disposable register(Mono<CommandSupport> response){
  return CommandSupportManagerProvider.supports.register("propertyRepair",new CommandSupportManagerProvider(){
   public String getProvider(){return "propertyRepair";}
   public Mono<? extends CommandSupport> getCommandSupport(String id,Map<String,Object> options){assertEquals("quality",id);return response;}
  });
 }
}
