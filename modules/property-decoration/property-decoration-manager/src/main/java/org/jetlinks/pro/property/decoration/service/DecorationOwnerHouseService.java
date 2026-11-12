package org.jetlinks.pro.property.decoration.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.pro.property.base.house.PropertyHouseholdInfo;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 业主先选房屋再选申请；公共档案负责本人关系，装修库只提供本人申请的轻量汇总。 */
@Service @RequiredArgsConstructor
public class DecorationOwnerHouseService {
    private final DecorationScope scope;
    private final QueryHelper queries;

    public record House(PropertyHouseholdInfo household, String projectName, long applicationCount, boolean processing) {}

    public Mono<List<House>> houses() {
        return scope.current().flatMap(ctx -> scope.projects().collectList().flatMap(projects ->
            authorizedHouses(projects).flatMap(homes -> {
                var query = scope.query(new QueryParamEntity(), ctx, projects.stream().map(PropertyOrganizationInfo::getId).toList());
                query.toNestQuery(n -> n.and("deleted", false).and("ownerUserId", ctx.userId()));
                query.setPaging(false); query.getSorts().clear();
                // 只聚合标量，不读取签署文书、附件、图纸或逐项审核详情。
                var counts = queries.select("""
                    select a.project_id,a.space_id,count(*) as application_count
                    from pd_application a group by a.project_id,a.space_id
                    """, HouseCount::new).where(query).fetch()
                    .collectMap(row -> key(row.getProjectId(), row.getSpaceId()));
                return Mono.zip(counts, occupiedHouses(ctx, homes)).map(tuple -> homes.stream().map(home -> {
                    var count = tuple.getT1().get(key(home.household().projectId(), home.household().spaceId()));
                    return new House(home.household(), home.projectName(), count == null ? 0 : count.getApplicationCount(),
                        tuple.getT2().contains(houseKey(ctx, home)));
                }).toList());
            })));
    }

    /** 只读取已由公共档案核验的本人房屋占用标志，与新增申请的租户+房屋唯一锁一致。
     * 不按申请登记账号或应用缩小占用范围；不返回其他业主、其他应用的申请或详情。
     */
    private Mono<Set<String>> occupiedHouses(DecorationScope.Context ctx, List<House> homes) {
        if (homes.isEmpty()) return Mono.just(Set.of());
        var query = QueryParamEntity.newQuery().where("tenantId", ctx.tenantId())
            .in("activeHouseKey", homes.stream().map(home -> houseKey(ctx, home)).toList()).getParam();
        return queries.select("select a.active_house_key from pd_application a", HouseOccupancy::new)
            .where(query).fetch().map(HouseOccupancy::getActiveHouseKey).collect(Collectors.toSet());
    }

    private static String houseKey(DecorationScope.Context ctx, House home) {
        return DecorationApplicationService.identity(ctx.tenantId() + ":" + home.household().spaceId());
    }

    private Mono<List<House>> authorizedHouses(List<PropertyOrganizationInfo> projects) {
        return scope.ownerHouses().flatMap(candidates -> {
            var spacesByProject = candidates.stream().map(row -> row.context().house())
                .collect(Collectors.groupingBy(home -> home.projectId(),
                    Collectors.mapping(home -> home.spaceId(), Collectors.toSet())));
            // 只核验本人有关且有权的项目；旧关系命令继续过滤空间权限并提供真实房屋类型。
            // 限制并发、保留认证上下文；任一目录故障向上传播，不返回部分房屋。
            return Flux.fromIterable(projects).filter(project -> spacesByProject.containsKey(project.getId()))
                .flatMapSequential(project -> scope.households(project.getId(), true)
                    .filter(home -> project.getId().equals(home.projectId()) && spacesByProject.get(project.getId()).contains(home.spaceId()))
                    .map(home -> new House(home, project.getName(), 0, false)), 8, 1)
                .distinct(home -> key(home.household().projectId(), home.household().spaceId())).collectList();
        });
    }

    /** 仍强制应用、租户、项目和本人边界；客户端只选择房屋，不传操作者身份。 */
    public Mono<PagerResult<ApplicationSummary>> applications(QueryParamEntity input) {
        return scope.current().flatMap(ctx -> scope.query(input, ctx).map(query -> {
            query.toNestQuery(n -> n.and("deleted", false).and("ownerUserId", ctx.userId()));
            return query;
        })).flatMap(query -> queries.select("""
            select a.id,a.project_id,a.space_id,a.number,a.space_name,a.status,a.refund_status,
              a.create_time,a.actual_end_time,a.decoration_type
            from pd_application a
            """, ApplicationSummary::new).where(query).fetchPaged(query.getPageIndex(), query.getPageSize()));
    }

    private static String key(String project, String space) { return project + ":" + space; }

    @Getter @Setter public static class HouseCount {
        private String projectId, spaceId;
        private long applicationCount;
    }
    @Getter @Setter public static class HouseOccupancy { private String activeHouseKey; }
    @Getter @Setter public static class ApplicationSummary {
        private String id, projectId, spaceId, number, spaceName, status, decorationType;
        private Integer refundStatus;
        private Long createTime, actualEndTime;
    }
}
