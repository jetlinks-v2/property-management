package org.jetlinks.pro.property.service;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.jetlinks.pro.property.api.CloudDirectory;
import org.jetlinks.pro.property.api.CloudDirectoryRecord;
import org.jetlinks.pro.property.entity.*;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Map;
import java.util.List;
import java.util.function.Function;

/** 按 Cloud 当前可访问目录同步共享源字段；业务扩展字段不参与源字段更新。批量save仅提交非空源字段，避免覆盖并发写入的扩展。 */
@Service @AllArgsConstructor
public class PropertySyncService {
 private final CloudDirectory directory;
 private final PropertyOrganizationService organizations;
 private final PropertySpaceService spaces;
 private final PropertyPersonService persons;
 public Mono<Map<String,Long>> sync() {
  return TenantHolder.currentId().switchIfEmpty(Mono.error(new AccessDenyException()))
   .flatMap(tenant -> syncOrganizations(tenant).flatMap(orgs -> syncPersons(tenant)
    .flatMap(users -> syncSpaces(tenant).map(space -> Map.of("organizations",orgs,"persons",users,"spaces",space)))));
 }
 private Mono<Long> syncOrganizations(String tenant) {
  return directory.organizations(tenant).buffer(200).concatMap(batch -> Mono.defer(() -> {
    List<PropertyOrganizationEntity> rows = batch.stream().map(source -> {
     PropertyOrganizationEntity row=new PropertyOrganizationEntity();
     row.setId(source.getId());row.setTenantId(tenant);row.setSourceName(source.getName());row.setParentId(source.getParentId());
     row.setSourcePath(source.getPath());row.setCode(source.getCode());row.setSourceEnabled(true);row.setSyncTime(System.currentTimeMillis());
     return row;
    }).toList();
    return organizations.save(rows).thenReturn((long)rows.size());
   })).reduce(0L,Long::sum);
 }
 private Mono<Long> syncPersons(String tenant) {
  return directory.persons(tenant).buffer(200).concatMap(batch -> Mono.defer(() -> {
    List<PropertyPersonEntity> rows=batch.stream().map(source -> {
     PropertyPersonEntity row=new PropertyPersonEntity();
     row.setId(source.getId());row.setTenantId(tenant);row.setSourceName(source.getName());row.setUsername(source.getUsername());
     row.setOrganizationIds(source.getOrganizationIds());row.setRoleIds(source.getRoleIds());row.setSourceStatus(source.getStatus());row.setSyncTime(System.currentTimeMillis());return row;
    }).toList();
    return persons.save(rows).thenReturn((long)rows.size());
   })).reduce(0L,Long::sum);
 }
 private Mono<Long> syncSpaces(String tenant) {
  return directory.spaces(tenant).buffer(200).concatMap(batch -> Mono.defer(() -> {
    List<PropertySpaceEntity> rows=batch.stream().map(source -> {
     PropertySpaceEntity row=new PropertySpaceEntity();
     row.setId(source.getId());row.setTenantId(tenant);row.setSourceName(source.getName());row.setParentId(source.getParentId());
     row.setSourcePath(source.getPath());row.setCode(source.getCode());row.setSpaceTypeId(source.getSpaceTypeId());row.setSourceEnabled(true);row.setSyncTime(System.currentTimeMillis());return row;
    }).toList();return spaces.save(rows).thenReturn((long)rows.size());
   })).reduce(0L,Long::sum);
 }
}
