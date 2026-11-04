package org.jetlinks.pro.property.integration;

import lombok.AllArgsConstructor;
import org.hswebframework.web.bean.FastBeanCopier;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.command.CommandServices;
import org.jetlinks.pro.property.api.CloudDirectory;
import org.jetlinks.pro.property.api.CloudDirectoryRecord;
import org.jetlinks.sdk.server.SdkServices;
import org.jetlinks.sdk.server.auth.AuthCommandSupportTypes;
import org.jetlinks.sdk.server.auth.UserDetail;
import org.jetlinks.sdk.server.auth.cmd.QueryUserDetailPageCommand;
import org.jetlinks.sdk.server.commons.cmd.QueryPagerCommand;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.function.IntFunction;

/** 通过平台现有 authService 与 spaceService 命令分页查询，禁止跨服务直接修改认证主表。 */
@Component
public class CommandCloudDirectory implements CloudDirectory {
 private static final int PAGE_SIZE = 200;
 @Override public Flux<CloudDirectoryRecord> organizations(String tenantId) {
  return CommandServices.getReactive(SdkServices.authService, AuthCommandSupportTypes.organization)
   .flatMapMany(support -> pages(page -> query(support, tenantId, page)))
   .cast(Object.class).map(source -> FastBeanCopier.copy(source, CloudDirectoryRecord.class));
 }
 @Override public Flux<CloudDirectoryRecord> spaces(String tenantId) {
  // 服务标识来自 JetLinks space-core 的 SpaceServices.Modules.SPACE。
  return CommandServices.getReactive("spaceService", "space")
   .flatMapMany(support -> pages(page -> query(support, tenantId, page)))
   .cast(Object.class).map(source -> FastBeanCopier.copy(source, CloudDirectoryRecord.class));
 }
 @Override public Flux<CloudDirectoryRecord> persons(String tenantId) {
  return CommandServices.getReactive(SdkServices.authService, AuthCommandSupportTypes.user)
   .flatMapMany(support -> pages(page -> {
    QueryUserDetailPageCommand command = new QueryUserDetailPageCommand();
    command.withQueryParam(parameter(tenantId, page));
    return support.execute(command);
   })).cast(Object.class).map(source -> person(FastBeanCopier.copy(source, UserDetail.class)));
 }
 private Mono<PagerResult<CloudDirectoryRecord>> query(CommandSupport support, String tenantId, int page) {
  QueryPagerCommand<CloudDirectoryRecord> command = QueryPagerCommand.of(CloudDirectoryRecord.class);
  command.withQueryParam(parameter(tenantId, page));
  return support.execute(command);
 }
 private QueryParamEntity parameter(String tenantId, int page) {
  return QueryParamEntity.of().toNestQuery().and("tenantId",tenantId)
   .orderByAsc("id").doPaging(page,PAGE_SIZE).getParam();
 }
 /** 每次只缓存一个源分页，不一次性收集整个租户目录。 */
 private <T> Flux<T> pages(IntFunction<Mono<PagerResult<T>>> query) {
  return query.apply(0).expand(page -> (long)(page.getPageIndex()+1)*page.getPageSize()<page.getTotal()
   ? query.apply(page.getPageIndex()+1) : Mono.empty()).concatMapIterable(PagerResult::getData);
 }
 private CloudDirectoryRecord person(UserDetail source) {
  CloudDirectoryRecord target = new CloudDirectoryRecord();
  target.setId(source.getId()); target.setName(source.getName()); target.setUsername(source.getUsername());
  target.setStatus(source.getStatus());
  target.setOrganizationIds(source.getOrgList()==null ? List.of() : source.getOrgList().stream().map(o->o.getId()).toList());
  target.setRoleIds(source.getRoleList()==null ? List.of() : source.getRoleList().stream().map(o->o.getId()).toList());
  return target;
 }
}
