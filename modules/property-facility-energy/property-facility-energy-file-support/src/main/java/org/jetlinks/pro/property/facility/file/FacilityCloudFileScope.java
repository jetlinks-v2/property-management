package org.jetlinks.pro.property.facility.file;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.assets.AssetManager;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.assets.scope.AssetScopeContext;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.io.file.FileInfo;
import org.jetlinks.pro.tenant.assets.TenantAssetsHolder;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Cloud file-owner authorization for facility attachments. Stored scope is immutable upload metadata;
 * organization read rights remain owned by AssetsHolder. No business or file tables are copied.
 * Shared files without the facility marker retain their platform behavior.
 */
@Component
@RequiredArgsConstructor
public class FacilityCloudFileScope {
  static final String KEY = "propertyFacilityEnergyScope";
  private final AssetManager assets;

  record Scope(String tenant, String application, String project) {
    Map<String, String> metadata() {
      return Map.of("tenant", tenant, "application", application, "project", project);
    }
  }

  Mono<Scope> current(String project) {
    return Mono.zip(TenantHolder.currentId(),
      AssetScopeContext.currentReactive().flatMap(c ->
        Mono.justOrEmpty(c.getScope(BusinessApplicationDimension.TYPE))),
      Authentication.currentReactive())
      .switchIfEmpty(Mono.error(new AccessDenyException()))
      .flatMap(context -> {
        if (project == null || project.isBlank()) return Mono.error(new AccessDenyException());
        // Owner H5 identity can read its customer directory but must not read staff work photos.
        if (List.of("facility-device", "facility-task", "facility-standard", "facility-fault").stream()
          .noneMatch(permission -> context.getT3().hasPermission(permission, "query"))) {
          return Mono.error(new AccessDenyException());
        }
        var scope = new Scope(context.getT1(), context.getT2().id(), project);
        var tenant = new TenantAssetsHolder(List.of(scope.tenant()), assets);
        return tenant.assertPermission0(AssetType.of("business_application"),
            List.of(scope.application()), CrudAssetPermission.read)
          .then(tenant.assertPermission0(AssetType.of("organization"), List.of(project), CrudAssetPermission.read))
          .then(AssetsHolder.assertPermission(AssetType.of("organization"), project, CrudAssetPermission.read))
          .thenReturn(scope);
      });
  }

  static Optional<Scope> stored(FileInfo info) {
    Object marker = info.getOthers() == null ? null : info.getOthers().get(KEY);
    if (marker == null) return Optional.empty();
    if (!(marker instanceof Map<?, ?> map)
      || !(map.get("tenant") instanceof String tenant)
      || !(map.get("application") instanceof String app)
      || !(map.get("project") instanceof String project)) throw new AccessDenyException();
    return Optional.of(new Scope(tenant, app, project));
  }

  Mono<Void> authorize(FileInfo info) {
    return Mono.defer(() -> stored(info).map(stored -> current(stored.project())
      .filter(stored::equals).switchIfEmpty(Mono.error(new AccessDenyException())).then())
      .orElseGet(Mono::empty));
  }

  Mono<Void> requireProject(FileInfo info, String project) {
    return Mono.defer(() -> {
      var stored = stored(info).orElseThrow(AccessDenyException::new);
      if (!Objects.equals(stored.project(), project)) return Mono.error(new AccessDenyException());
      return Mono.empty();
    });
  }
}
