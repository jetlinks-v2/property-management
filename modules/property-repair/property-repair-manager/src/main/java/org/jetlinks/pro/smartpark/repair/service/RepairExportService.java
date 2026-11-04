package org.jetlinks.pro.smartpark.repair.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.crud.query.QueryHelper;
import org.hswebframework.web.exception.NotFoundException;
import org.hswebframework.web.id.IDGenerator;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.smartpark.repair.asset.RepairAssetType;
import org.jetlinks.pro.smartpark.repair.entity.RepairExportEntity;
import org.jetlinks.pro.io.file.FileManager;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Objects;

/** 请求内流式生成有界导出。导出和下载分别拥有真实的数据权限边界。 */
@Service @AllArgsConstructor @Slf4j
public class RepairExportService {
    static final int MAX_ROWS=RepairExportContent.MAX_ROWS;
    private final RepairContextProvider contexts;
    private final RepairExportRepository exports;
    private final RepairOrderService orders;
    private final FileManager files;
    private final RepairReportService reports;
    private final RepairReviewQueryService reviews;
    private final QueryHelper helper;
    private final RepairExportScope visibility;

    public Mono<RepairExportEntity> create(QueryParamEntity query,RepairExportType type,String format,Integer reviewStatus,String reviewKind){
        RepairFlowRules.require(java.util.Set.of("xlsx","csv").contains(format),"export_format_invalid");
        QueryParamEntity scoped=type==RepairExportType.paid
            ?query.toNestQuery().and("paid",true).getParam():query;
        scoped.setPaging(true);scoped.setPageIndex(0);scoped.setPageSize(MAX_ROWS+1);
        if(scoped.getSorts().isEmpty())scoped.toQuery().orderByDesc("createTime").orderByAsc("id");
        return contexts.current().flatMap(context->{
            RepairExportEntity task=new RepairExportEntity();task.setId(IDGenerator.MD5.generate());
            task.setTenantId(context.tenantId());task.setApplicationId(context.applicationId());
            task.setCreatorId(context.userId());task.setCreatorName(context.userName());
            task.setName(context.userName()+"-"+type.title()+"-"+System.currentTimeMillis()+"."+format);
            task.setExportType(type.name());task.setStatus("processing");task.setCreateTime(System.currentTimeMillis());
            Mono<Integer> count=type==RepairExportType.review?Mono.just(0):orders.createQuery().setParam(scoped).count();
            return exports.insert(task).then(count)
                .doOnNext(size->RepairFlowRules.require(size<=MAX_ROWS,"export_limit"))
                .flatMap(ignored->generate(task,scoped,type,format,reviewStatus,reviewKind))
                .onErrorResume(error->exports.createUpdate().set(RepairExportEntity::getStatus,"failed")
                    .set(RepairExportEntity::getCompleteTime,System.currentTimeMillis()).where(RepairExportEntity::getId,task.getId())
                    .execute().then(Mono.error(error)));
        });
    }
    private Flux<RepairExportTables.Row> rows(QueryParamEntity scoped,RepairExportType type,Integer reviewStatus,String reviewKind){
        scoped.setPaging(false);
        if(type.aggregated()) {
            var columns=RepairExportTables.report(type);
            return reports.exportRows(scoped).map(row->RepairExportTables.row((RepairReportRow)row,columns,row.sourceOrderIds()));
        }
        if(type==RepairExportType.review) return reviews.exportRows(scoped,reviewStatus,reviewKind)
            .map(row->RepairExportTables.row(row,RepairExportTables.REVIEWS,java.util.List.of(row.getId())));
        if(type==RepairExportType.evaluation) return orders.createQuery().setParam(scoped).fetch()
            .map(row->RepairExportTables.row(row,RepairExportTables.EVALUATIONS,java.util.List.of(row.getId())));
        // 与旧版相同，导出关联当前分类层级。单次JOIN保持流式读取，避免逐工单查字典。
        String sql="select sp_repair_order.*,root.name as \"firstCategoryName\",parent.name as \"secondCategoryName\" "
            +"from sp_repair_order left join sp_repair_configuration leaf on leaf.id=sp_repair_order.category_id "
            +"and leaf.kind='category' and leaf.application_id=sp_repair_order.application_id "
            +"and leaf.business_project_id=sp_repair_order.business_project_id and leaf.tenant_id=sp_repair_order.tenant_id "
            +"left join sp_repair_configuration parent on parent.id=leaf.parent_id and parent.kind='category' "
            +"and parent.application_id=leaf.application_id and parent.business_project_id=leaf.business_project_id and parent.tenant_id=leaf.tenant_id "
            +"left join sp_repair_configuration root on root.id=parent.parent_id and root.kind='category' "
            +"and root.application_id=leaf.application_id and root.business_project_id=leaf.business_project_id and root.tenant_id=leaf.tenant_id";
        return helper.select(sql,RepairOrderExportRow::new).where(scoped).fetch()
            .map(row->RepairExportTables.row(row,RepairExportTables.ORDERS,java.util.List.of(row.getId())));
    }
    private Mono<RepairExportEntity> generate(RepairExportEntity task,QueryParamEntity scoped,RepairExportType type,String format,Integer reviewStatus,String reviewKind){
        RepairExportDocument content=new RepairExportDocument();
        return files.saveFile(task.getName(),content.encode(type,format,rows(scoped,type,reviewStatus,reviewKind))).flatMap(file->{
            task.setOrderIds(content.orderIds());task.setSourceCount(task.getOrderIds().size());task.setRowCount(content.rowCount());
            task.setFileId(file.getId());task.setStatus("completed");task.setCompleteTime(System.currentTimeMillis());
            return exports.updateById(task.getId(),task).thenReturn(task);
        });
    }
    public Mono<PagerResult<RepairExportEntity>> query(QueryParamEntity query){
        query.setPaging(true);query.setPageIndex(Math.max(0,query.getPageIndex()));
        query.setPageSize(Math.min(100,Math.max(1,query.getPageSize())));
        return contexts.current().flatMap(context->visibility.inject(query,context)).flatMap(exports::queryPager);
    }
    public Mono<RepairExportEntity> visible(String id){
        return contexts.current().flatMap(context->visibility.inject(QueryParamEntity.newQuery().where("id",id).getParam(),context))
            .flatMap(query->exports.createQuery().setParam(query).fetchOne())
            .switchIfEmpty(Mono.error(new NotFoundException()));
    }
    public Mono<RepairExportEntity> authorizeDownload(RepairExportEntity task){
        RepairFlowRules.require("completed".equals(task.getStatus()),"export_not_ready");
        // 角色撤权后禁止继续下载旧文件；即使导出人仍有菜单权限，也不能复用旧授权快照。
        // 最大20万条不一次展开成SQL IN；所有批次通过之前不向客户端输出文件字节。
        Mono<Void> allowed=contexts.current().flatMap(visibility::holder).flatMap(holder ->
            Flux.fromIterable(task.getOrderIds()).buffer(500)
                .concatMap(ids->holder.assertPermission0(RepairAssetType.repairOrder,ids,CrudAssetPermission.read)).then());
        return allowed.thenReturn(task);
    }
    public Flux<DataBuffer> download(RepairExportEntity task){
        return files.read(task.getFileId());
    }
}
