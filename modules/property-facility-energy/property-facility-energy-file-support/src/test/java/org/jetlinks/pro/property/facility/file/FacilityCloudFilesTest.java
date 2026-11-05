package org.jetlinks.pro.property.facility.file;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.authorization.simple.*;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.impl.CreatorAssetsHolder;
import org.jetlinks.pro.assets.scope.*;
import org.jetlinks.pro.io.file.*;
import org.jetlinks.pro.property.facility.command.AssertFacilityFilesCommand;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** Real holder allow/deny and actual Spring SPI advice; only asset storage and binary storage are substituted. */
class FacilityCloudFilesTest {
  final AssetManager assets = mock(AssetManager.class);
  final FacilityCloudFileScope scope = new FacilityCloudFileScope(assets);
  String organizationOwner = "worker";
  boolean employeePermissions = true;

  FileInfo file(boolean protectedFile) {
    var info = new FileInfo();
    info.setId("file");
    info.setName("task.jpg");
    if (protectedFile) info.withOther(FacilityCloudFileScope.KEY,
      new FacilityCloudFileScope.Scope("tenant", "app", "project").metadata());
    return info;
  }

  Context access() {
    var user = new SimpleUser();
    user.setId("worker");
    var auth = new SimpleAuthentication();
    auth.setUser(user);
    if (employeePermissions) {
      var permission = new SimplePermission();
      permission.setId("facility-task");
      permission.setActions(Set.of("query"));
      auth.setPermissions(List.of(permission));
    }
    auth.setDimensions(List.of(SimpleDimension.of("app", "App",
      org.jetlinks.pro.authorize.BusinessApplicationDimension.INSTANCE,
      Map.of(AssetScopeSources.POLICY_OPTION_KEY, Map.of("*", "IGNORE")))));
    var active = new AssetScopeResolverManager()
      .resolve(AssetScope.of("business_application", "app"), auth).block();
    when(assets.getAssets(any(AssetType.class), anyCollection())).thenAnswer(call -> {
      AssetType type = call.getArgument(0);
      Collection<?> ids = call.getArgument(1);
      return Flux.fromIterable(ids).map(id -> new DefaultAsset(id.toString(), id.toString(), type)
        .withOption("tenantId", "tenant")
        .withOption("creatorId", type.getId().equals("organization") ? organizationOwner : "worker"));
    });
    return Context.of(Authentication.class, active.authentication(), AssetsHolder.class, new CreatorAssetsHolder(user, assets));
  }

  <T> Flux<T> as(Flux<T> work, String tenant, String app) {
    return work.contextWrite(access()).contextWrite(TenantHolder.makeCurrent(tenant))
      .contextWrite(AssetScopeContext.replace(AssetScopeContext.of(AssetScope.of("business_application", app))));
  }

  @Test
  void markedFilesRequireMatchingTenantApplicationAndCurrentOrganizationPermission() {
    StepVerifier.create(as(scope.authorize(file(true)).flux(), "tenant", "app")).verifyComplete();
    StepVerifier.create(as(scope.authorize(file(true)).flux(), "other", "app"))
      .expectError(AccessDenyException.class).verify();
    StepVerifier.create(as(scope.authorize(file(true)).flux(), "tenant", "other"))
      .expectError(AccessDenyException.class).verify();
    organizationOwner = "other-worker";
    StepVerifier.create(as(scope.authorize(file(true)).flux(), "tenant", "app"))
      .expectError(AccessDenyException.class).verify();
    StepVerifier.create(scope.authorize(file(true))).expectError(AccessDenyException.class).verify();
    organizationOwner = "worker";
    employeePermissions = false;
    StepVerifier.create(as(scope.authorize(file(true)).flux(), "tenant", "app"))
      .expectError(AccessDenyException.class).verify();
  }

  @Test
  void uploadListenerPersistsProtectedScopeWithoutPublicAccessAndLeavesOtherUploadsAlone() {
    var listener = new FacilityCloudFileListener();
    var info = file(false);
    info.setOptions(new FileOption[]{FileOption.publicAccess});
    listener.beforeUpload(info).contextWrite(c -> c.put(FacilityCloudFileScope.Scope.class,
      new FacilityCloudFileScope.Scope("tenant", "app", "project"))).block();
    assertEquals("project", FacilityCloudFileScope.stored(info).orElseThrow().project());
    assertFalse(info.hasOption(FileOption.publicAccess));
    var unrelated = file(false);
    unrelated.setOptions(new FileOption[]{FileOption.publicAccess});
    listener.beforeUpload(unrelated).block();
    assertTrue(unrelated.hasOption(FileOption.publicAccess));
    StepVerifier.create(scope.authorize(unrelated)).verifyComplete();
  }

  @Test
  void actualSpiAdviceRejectsMetadataBinaryAndCompressionBeforeBytesAreConsumed() {
    var target = mock(FileManager.class);
    when(target.getFile("file")).thenReturn(Mono.just(file(true)));
    var read = new AtomicBoolean();
    Flux<DataBuffer> bytes = Flux.defer(() -> {
      read.set(true);
      return Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(new byte[]{42}));
    });
    when(target.read("file")).thenReturn(bytes);
    when(target.compress(eq(List.of("file")), any())).thenReturn(bytes);
    var factory = new AspectJProxyFactory(target);
    factory.addAspect(new FacilityCloudFileGuard(scope));
    FileManager guarded = factory.getProxy();
    StepVerifier.create(as(guarded.getFile("file").flux(), "tenant", "other"))
      .expectError(AccessDenyException.class).verify();
    StepVerifier.create(as(guarded.read("file"), "tenant", "other"))
      .expectError(AccessDenyException.class).verify();
    StepVerifier.create(as(guarded.compress(List.of("file"), ctx -> Mono.empty()), "tenant", "other"))
      .expectError(AccessDenyException.class).verify();
    assertFalse(read.get());
    StepVerifier.create(as(guarded.read("file"), "tenant", "app"))
      .assertNext(buffer -> assertEquals(42, buffer.read())).verifyComplete();
    assertTrue(read.get());
  }

  @Test
  void associationCommandRejectsUnprotectedOrOtherProjectFiles() {
    var files = mock(FileManager.class);
    var command = new AssertFacilityFilesCommand();
    command.setFileIds(List.of("file", "file"));
    command.setProjectId("project");
    var support = new FacilityCloudFilesCommandSupport(files, scope);
    when(files.getFile("file")).thenReturn(Mono.just(file(false)));
    StepVerifier.create(support.validate(command)).expectError(AccessDenyException.class).verify();
    when(files.getFile("file")).thenReturn(Mono.just(file(true)));
    command.setProjectId("other-project");
    StepVerifier.create(support.validate(command)).expectError(AccessDenyException.class).verify();
    command.setProjectId("project");
    StepVerifier.create(support.validate(command)).expectNext(true).verifyComplete();
  }
}
