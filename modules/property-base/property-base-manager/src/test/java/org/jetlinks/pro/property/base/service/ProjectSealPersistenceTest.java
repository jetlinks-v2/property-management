package org.jetlinks.pro.property.base.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.property.base.entity.PropertyOrganizationEntity;
import org.jetlinks.pro.property.base.organization.OrganizationType;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.test.context.*;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;
import reactor.util.context.Context;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 独立PG验证项目类型、权限、CAS与图片引用；只替换公共文件传输，不修改业务库。 */
@SpringBootTest(classes={OrganizationPersistenceTest.Config.class,ProjectSealPersistenceTest.Config.class},webEnvironment=SpringBootTest.WebEnvironment.NONE)
class ProjectSealPersistenceTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){OrganizationPersistenceTest.database(r);}
    @Autowired ReactiveRepository<PropertyOrganizationEntity,String> organizations;
    @Autowired ReactiveRepository<org.jetlinks.pro.property.base.entity.PropertySpaceBindingEntity,String> bindings;
    @Autowired PropertyOrganizationService orgs;
    @Autowired PropertyProjectSealService seals;
    @Autowired PropertyProjectSealStorage files;
    @BeforeEach void prepare(){
        bindings.deleteById(bindings.createQuery().fetch().map(org.jetlinks.pro.property.base.entity.PropertySpaceBindingEntity::getId)).block();
        organizations.deleteById(organizations.createQuery().fetch().map(PropertyOrganizationEntity::getId)).block(); reset(files);
        for(String id:Set.of("project","department")){
            var row=new PropertyOrganizationEntity();row.setId(id);row.setName(id);row.setDeleted(false);row.setVersion(0L);
            if(id.equals("project"))row.setOrganizationType(OrganizationType.project);organizations.insert(row).block();
        }
    }
    @Test void projectOnlyImageUploadPrivateReadAndCas() throws Exception {
        byte[] image=png(12,12);
        when(files.save(anyString(),any())).thenReturn(Mono.just("file-1"));when(files.read("file-1")).thenReturn(Mono.just(image));
        when(files.delete(anyString())).thenReturn(Mono.empty());
        assertEquals(0,seals.read("project").contextWrite(access(true)).flux().count().block());
        var row=seals.upload("project",0,file(image)).contextWrite(access(true)).block();
        assertEquals("file-1",row.getProjectSealFileId());assertEquals(1L,row.getVersion());
        var seal=seals.read("project").contextWrite(access(true)).block();assertArrayEquals(image,seal.content());assertEquals("image/png",seal.mediaType());
        StepVerifier.create(orgs.updateSeal("project",0,"stale","image/png").contextWrite(access(true)))
            .expectErrorMatches(e->e.getMessage().contains("version_conflict")).verify();
        StepVerifier.create(orgs.updateType("project",OrganizationType.external,1).contextWrite(access(true)))
            .expectErrorMatches(e->e.getMessage().contains("seal_remove_before_type")).verify();
        assertNull(seals.remove("project",1).contextWrite(access(true)).block().getProjectSealFileId());
        assertEquals(OrganizationType.external,orgs.updateType("project",OrganizationType.external,2).contextWrite(access(true)).block().getOrganizationType());
    }
    @Test void nonProjectAndDeniedCannotUploadOrRead() throws Exception {
        byte[] image=png(8,8);
        StepVerifier.create(seals.upload("department",0,file(image)).contextWrite(access(true))).expectErrorMatches(e->e.getMessage().contains("seal_project_only")).verify();
        StepVerifier.create(seals.upload("project",0,file(image)).contextWrite(access(false))).expectError(AccessDenyException.class).verify();
        StepVerifier.create(seals.read("project").contextWrite(access(false))).expectError(AccessDenyException.class).verify();
        verifyNoInteractions(files);
    }
    @Test void rejectsInvalidAndOversizedImageBeforeStorage() throws Exception {
        assertThrows(RuntimeException.class,()->PropertyProjectSealService.imageType(new byte[]{1,2,3}));
        assertThrows(RuntimeException.class,()->PropertyProjectSealService.imageType(new byte[PropertyProjectSealService.MAX_BYTES+1]));
        assertThrows(RuntimeException.class,()->PropertyProjectSealService.imageType(png(4097,1)));
        StepVerifier.create(seals.upload("project",0,file(new byte[]{1,2,3})).contextWrite(access(true)))
            .expectErrorMatches(e->e.getMessage().contains("seal_invalid")).verify();
        verifyNoInteractions(files);
    }
    @Test void replacementAndRemovalPreserveHistoricalFilesAndStaleVersionDoesNotUpload() throws Exception {
        when(files.save(anyString(),any())).thenReturn(Mono.just("original"),Mono.just("replacement"));
        var original=seals.upload("project",0,file(png(8,8))).contextWrite(access(true)).block();
        assertEquals("original",original.getProjectSealFileId());
        var replaced=seals.upload("project",1,file(png(10,10))).contextWrite(access(true)).block();
        assertEquals("replacement",replaced.getProjectSealFileId());assertEquals(2L,replaced.getVersion());
        StepVerifier.create(seals.upload("project",1,file(png(12,12))).contextWrite(access(true)))
            .expectErrorMatches(e->e.getMessage().contains("version_conflict")).verify();
        StepVerifier.create(seals.remove("project",1).contextWrite(access(true)))
            .expectErrorMatches(e->e.getMessage().contains("version_conflict")).verify();
        assertEquals("replacement",organizations.findById("project").block().getProjectSealFileId());
        var removed=seals.remove("project",2).contextWrite(access(true)).block();
        assertNull(removed.getProjectSealFileId());assertNull(removed.getProjectSealMediaType());assertEquals(3L,removed.getVersion());
        verify(files,times(2)).save(anyString(),any());verify(files,never()).delete(anyString());
    }
    @Test void concurrentVersionChangeReclaimsOnlyNewUploadAndPreservesCurrentSeal() throws Exception {
        when(files.save(anyString(),any())).thenAnswer(invocation ->
            orgs.updateSeal("project",0,"current","image/png").thenReturn("new-unbound"));
        when(files.delete("new-unbound")).thenReturn(Mono.empty());
        StepVerifier.create(seals.upload("project",0,file(png(8,8))).contextWrite(access(true)))
            .expectErrorMatches(e->e.getMessage().contains("version_conflict")).verify();
        var current=organizations.findById("project").block();
        assertEquals("current",current.getProjectSealFileId());assertEquals(1L,current.getVersion());
        verify(files).delete("new-unbound");verify(files,never()).delete("current");
    }
    @Test void removeRequiresAssetWriteAndOversizedMultipartNeverReachesStorage() {
        StepVerifier.create(seals.remove("project",0).contextWrite(access(false)))
            .expectError(AccessDenyException.class).verify();
        StepVerifier.create(seals.upload("project",0,file(new byte[PropertyProjectSealService.MAX_BYTES+1])).contextWrite(access(true)))
            .expectErrorMatches(e->e.getMessage().contains("seal_invalid")).verify();
        verifyNoInteractions(files);
    }
    private static byte[] png(int width,int height) throws Exception {
        var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(width,height,2),"png",out);return out.toByteArray();
    }
    private FilePart file(byte[] image){var file=mock(FilePart.class);when(file.content()).thenReturn(Flux.defer(()->Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(image))));return file;}
    private Context access(boolean allowed){
        var holder=mock(AssetsHolder.class);when(holder.assertPermission(anyString(),anyCollection(),any())).thenReturn(allowed?Mono.empty():Mono.error(new AccessDenyException()));
        var user=new SimpleUser();user.setId("operator");user.setUsername("operator");var auth=new SimpleAuthentication();auth.setUser(user);
        return Context.of(Authentication.class,auth,AssetsHolder.class,holder);
    }
    @Configuration
    static class Config {@Bean PropertyProjectSealStorage sealFiles(){return mock(PropertyProjectSealStorage.class);}}
}
