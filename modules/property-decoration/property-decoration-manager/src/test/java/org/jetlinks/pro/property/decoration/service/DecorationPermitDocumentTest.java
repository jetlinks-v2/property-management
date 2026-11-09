package org.jetlinks.pro.property.decoration.service;

import org.jetlinks.pro.property.decoration.entity.DecorationApplicationEntity;
import org.jetlinks.pro.property.decoration.model.DecorationModels;
import org.jetlinks.pro.property.decoration.model.DecorationProjectSealGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DecorationPermitDocumentTest {
    private final DecorationScope.Context context=new DecorationScope.Context("tenant","app","actor","name",null);
    private DecorationApplicationEntity paid() {
        var row=new DecorationApplicationEntity();row.setProjectId("project");row.setPaymentStatus(2);row.setDepositReviewStatus(1);
        var details=new DecorationModels.ApplicationDetails();details.setMaterials(List.of());row.setDetails(details);row.setPermit(new DecorationModels.Permit());return row;
    }
    private DecorationPermitDocumentService service(DecorationProjectSealGateway gateway) {
        var beans=new DefaultListableBeanFactory();if(gateway!=null)beans.registerSingleton("seals",gateway);
        return new DecorationPermitDocumentService(mock(DecorationApplicationService.class),mock(DecorationScope.class),beans.getBeanProvider(DecorationProjectSealGateway.class));
    }
    private void error(Mono<?> source,String code) {
        StepVerifier.create(source).expectErrorMatches(error->error.getMessage().contains("error.decoration."+code)).verify();
    }
    @Test void snapshotUploadValidatesActualPngContent() throws Exception {
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2240,1480,java.awt.image.BufferedImage.TYPE_INT_ARGB),"png",bytes);
        assertDoesNotThrow(()->DecorationAttachmentService.validatePermitImage(bytes.toByteArray()));
        assertThrows(org.hswebframework.web.exception.BusinessException.class,()->DecorationAttachmentService.validatePermitImage("pretend.png".getBytes()));
        assertThrows(org.hswebframework.web.exception.BusinessException.class,()->DecorationAttachmentService.validatePermitImage(java.util.Arrays.copyOf(bytes.toByteArray(),12)));
    }
    @Test void missingAdapterOrMissingProjectSealNeverProducesAnUnsignedDocument() {
        error(service(null).resolve(paid(),context),"project_seal_unavailable");
        error(service(request->Mono.empty()).resolve(paid(),context),"project_seal_required");
    }
    @Test void sealScopeAndActualImageAreValidatedWithoutFetchingClientUrls() throws Exception {
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_ARGB),"png",bytes);
        byte[] png=bytes.toByteArray();
        var good=new DecorationProjectSealGateway.Seal("app","tenant","project","image/png",png);
        var document=service(request->{assertEquals(new DecorationProjectSealGateway.Request("app","tenant","project"),request);return Mono.just(good);}).resolve(paid(),context).block();
        assertEquals(64,document.sealDigest().length());assertArrayEquals(png,java.util.Base64.getDecoder().decode(document.sealDataUrl().split(",")[1]));
        for(var wrong:List.of(new DecorationProjectSealGateway.Seal("other","tenant","project","image/png",png),new DecorationProjectSealGateway.Seal("app","other","project","image/png",png),new DecorationProjectSealGateway.Seal("app","tenant","other","image/png",png)))
            error(service(request->Mono.just(wrong)).resolve(paid(),context),"project_seal_mismatch");
        for(var invalid:List.of(new DecorationProjectSealGateway.Seal("app","tenant","project","image/svg+xml","<svg>bad</svg>".getBytes()),new DecorationProjectSealGateway.Seal("app","tenant","project","image/png",java.util.Arrays.copyOf(png,12)),new DecorationProjectSealGateway.Seal("app","tenant","project","image/png",new byte[2*1024*1024+1])))
            error(service(request->Mono.just(invalid)).resolve(paid(),context),"project_seal_invalid");
    }
}
