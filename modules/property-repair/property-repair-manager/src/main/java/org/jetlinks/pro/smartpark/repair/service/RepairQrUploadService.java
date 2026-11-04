package org.jetlinks.pro.smartpark.repair.service;

import lombok.AllArgsConstructor;
import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.web.id.IDGenerator;
import org.jetlinks.pro.io.file.FileManager;
import org.jetlinks.pro.smartpark.repair.entity.RepairQrUploadEntity;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** 二维码限定的私有附件入口；回执24小时有效，成功提交后由工单持有文件。 */
@Service @AllArgsConstructor
public class RepairQrUploadService {
    private static final long MAX_BYTES=50L*1024*1024;
    private static final Set<String> EXTENSIONS=Set.of("jpg","jpeg","png","gif","webp","heic","mp4","mov","mp3","wav","m4a","amr","pdf","doc","docx","xls","xlsx","ppt","pptx","txt","zip","rar","7z");
    private final RepairQrGrantProvider grants;
    private final RepairQrUploadRepository uploads;
    private final RepairQrAssetBinder binder;
    private final FileManager files;

    public Mono<String> upload(String code,FilePart part) {
        String name=part.filename();String extension=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        RepairFlowRules.require(EXTENSIONS.contains(extension),"qr_attachment_type_invalid");
        return grants.resolve(code).flatMap(grant -> {
            long[] size={0};
            Flux<DataBuffer> content=part.content().<DataBuffer>handle((buffer,sink)->{
                size[0]+=buffer.readableByteCount();
                if(size[0]>MAX_BYTES){DataBufferUtils.release(buffer);sink.error(new BusinessException("error.repair.qr_attachment_too_large",413));}
                else sink.next(buffer);
            }).doOnDiscard(DataBuffer.class,DataBufferUtils::release);
            return files.saveFile("报事附件-"+IDGenerator.MD5.generate()+"."+extension,content)
                .flatMap(file -> binder.bind("file",file.getId(),grant).then(Mono.defer(()->{
                    RepairQrUploadEntity receipt=new RepairQrUploadEntity();receipt.setId(IDGenerator.MD5.generate());
                    receipt.setQrId(grant.qr.getId());receipt.setFileId(file.getId());receipt.setExpiresAt(System.currentTimeMillis()+86_400_000L);
                    return uploads.insert(receipt).thenReturn(receipt.getId());
                })).onErrorResume(error->files.delete(file.getId()).then(Mono.error(error))))
                .contextWrite(TenantHolder.makeCurrent(grant.context().tenantId()));
        });
    }
    Mono<List<RepairQrUploadEntity>> receipts(List<String> ids,RepairQrGrantProvider.Grant grant) {
        return Flux.fromIterable(ids).distinct().concatMap(id -> uploads.findById(id)
            .filter(row -> Objects.equals(row.getQrId(),grant.qr.getId()) && row.getOrderId()==null && row.getExpiresAt()>System.currentTimeMillis())
            .switchIfEmpty(Mono.error(new BusinessException("error.repair.qr_attachment_invalid",400))))
            .collectList();
    }
    Mono<Void> consume(List<RepairQrUploadEntity> receipts,String orderId) {
        return Flux.fromIterable(receipts).concatMap(receipt -> uploads.createUpdate()
            .set(RepairQrUploadEntity::getOrderId,orderId).where(RepairQrUploadEntity::getId,receipt.getId())
            .isNull(RepairQrUploadEntity::getOrderId).gt(RepairQrUploadEntity::getExpiresAt,System.currentTimeMillis()).execute()
            .doOnNext(changed -> RepairFlowRules.require(changed==1,"qr_attachment_invalid"))).then();
    }
    /** 有界清理未提交附件；与提交共享到期判定，已被工单消费的文件永不清理。 */
    public Mono<Void> cleanup() {
        var query=org.hswebframework.web.api.crud.entity.QueryParamEntity.newQuery().lte(RepairQrUploadEntity::getExpiresAt,System.currentTimeMillis())
            .nest().isNull(RepairQrUploadEntity::getOrderId).or("orderId","__expired__").end()
            .doPaging(0,100).getParam();
        return uploads.createQuery().setParam(query).fetch()
            .concatMap(receipt -> uploads.createUpdate().set(RepairQrUploadEntity::getOrderId,"__expired__")
                .where(RepairQrUploadEntity::getId,receipt.getId()).lte(RepairQrUploadEntity::getExpiresAt,System.currentTimeMillis())
                .nest().isNull(RepairQrUploadEntity::getOrderId).or("orderId","__expired__").end().execute()
                .filter(changed->changed==1).flatMap(ignore->files.delete(receipt.getFileId()).then(uploads.createDelete()
                    .where(RepairQrUploadEntity::getId,receipt.getId()).and(RepairQrUploadEntity::getOrderId,"__expired__").execute()))).then();
    }
}
