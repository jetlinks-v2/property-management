package org.jetlinks.pro.property.decoration.service;

import org.jetlinks.pro.property.base.house.PropertyHouseholdInfo;
import org.jetlinks.pro.property.decoration.model.DecorationModels;
import org.jetlinks.pro.property.decoration.model.DecorationRequests;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DecorationSelfInspectionFormTest {
    private DecorationRequests.Create input() {
        var request = new DecorationRequests.Create(); request.setContactPhone("13800000000");
        var items = new ArrayList<DecorationModels.SelfInspectionItem>();
        for (String key : List.of("phoneLine", "cableTvLine", "broadbandLine", "videoIntercom", "doorMagnet", "other", "kitchenDrainage", "masterBathroomDrainage", "balconyDrainage")) {
            var item = new DecorationModels.SelfInspectionItem(); item.setKey(key); item.setValue(true); items.add(item);
        }
        request.setSelfInspectionItems(items);
        return request;
    }

    @Test void fullChecklistAndDerivedResultAreFrozenInsteadOfGenericSummary() {
        var input = input(); input.getSelfInspectionItems().get(6).setValue(false); input.setSelfInspectionStatus(0);
        var home = new PropertyHouseholdInfo("project", "house", "一栋101", "residential", "customer", "owner", "业主", "13800000000", "relation", "owner");
        var content = DecorationDocuments.content("selfInspection", input, home, new DecorationModels.Agreement());
        assertEquals("装修自检表\n房屋：一栋101\n业主：业主\n联系电话：13800000000\n\n检测项目是否完好\n电话线：是\n闭路线：是\n宽带线路：是\n可视分机：是\n门磁：是\n其他：是\n\n排水是否通畅\n厨房排水：否\n主卫排水：是\n生活阳台排水：是\n\n处理状态：有问题", content);
        assertEquals(1, DecorationSelfInspectionForm.status(DecorationSelfInspectionForm.snapshot(input)));
        input.setConstructionUnit("后续填写的施工单位");
        assertEquals(content, DecorationDocuments.content("selfInspection", input, home, new DecorationModels.Agreement()));
    }

    @Test void missingDuplicatedAndUnknownItemsCannotReplaceTheNineConfirmedItems() {
        var missing = input(); missing.getSelfInspectionItems().remove(0);
        assertThrows(org.hswebframework.web.exception.BusinessException.class, () -> DecorationSelfInspectionForm.snapshot(missing));
        var duplicate = input(); duplicate.getSelfInspectionItems().get(0).setKey("cableTvLine");
        assertThrows(org.hswebframework.web.exception.BusinessException.class, () -> DecorationSelfInspectionForm.snapshot(duplicate));
        duplicate.getSelfInspectionItems().get(0).setKey("untrusted");
        assertThrows(org.hswebframework.web.exception.BusinessException.class, () -> DecorationSelfInspectionForm.snapshot(duplicate));
    }

    @Test void pcAcceptsImportedSelfInspectionWithoutOnlineDocuments() {
        var input = new DecorationRequests.Create();
        var ctx = new DecorationScope.Context("tenant", "app", "staff", "员工", null);
        assertThrows(org.hswebframework.web.exception.BusinessException.class,
            () -> DecorationDocuments.freeze(input, null, List.of(), ctx, false));
        input.setSelfInspectionFiles(List.of("offline-signed-file"));
        assertTrue(DecorationDocuments.freeze(input, null, List.of(), ctx, false).isEmpty());
    }

    @Test void ownerSignsActualEnabledMaterialsAndTemplateChangesInvalidateSignature() {
        var input = input(); input.setStartTime(1794096000000L); input.setCompletionTime(1794182400000L);
        var home = new PropertyHouseholdInfo("project", "house", "一栋101", "residential", "customer", "owner", "业主", "13800000000", "relation", "owner");
        var ctx = new DecorationScope.Context("tenant", "app", "owner", "业主", null);
        var material = new DecorationModels.MaterialDefinition(); material.setId("custom"); material.setName("项目资料");
        material.setKind("material"); material.setEnabled(true); material.setTemplateFileId("template-1");
        var drawing = new DecorationModels.MaterialDefinition(); drawing.setId("drawing"); drawing.setKind("drawing"); drawing.setEnabled(true);
        var disabled = new DecorationModels.MaterialDefinition(); disabled.setId("disabled"); disabled.setKind("material");
        var definitions = List.of(material, drawing, disabled);
        var upload = new DecorationRequests.MaterialUpload(); upload.setItemId("custom"); upload.setFileIds(List.of("material-image"));
        var drawingUpload = new DecorationRequests.MaterialUpload(); drawingUpload.setItemId("drawing"); drawingUpload.setFileIds(List.of("drawing-file"));
        input.setMaterials(List.of(upload, drawingUpload));
        var self = new DecorationRequests.SignedDocument(); self.setKind("selfInspection");
        self.setContent(DecorationDocuments.content("selfInspection", input, home, definitions));
        self.setSignatureFileId("self-signature"); self.setDocumentFileId("self-image"); input.setSignedDocuments(List.of(self));
        assertThrows(org.hswebframework.web.exception.BusinessException.class,
            () -> DecorationDocuments.freeze(input, home, definitions, ctx, true));
        var signed = new DecorationRequests.SignedDocument(); signed.setKind("material:custom");
        signed.setContent(DecorationDocuments.content(signed.getKind(), input, home, definitions));
        signed.setSignatureFileId("material-signature"); signed.setDocumentFileId("material-image"); input.setSignedDocuments(List.of(self, signed));
        var frozen = DecorationDocuments.freeze(input, home, definitions, ctx, true);
        assertEquals(List.of("装修自检表", "项目资料"), frozen.stream().map(DecorationModels.SignedDocument::getTitle).toList());
        upload.setFileIds(List.of("different-upload"));
        assertThrows(org.hswebframework.web.exception.BusinessException.class,
            () -> DecorationDocuments.freeze(input, home, definitions, ctx, true));
        upload.setFileIds(List.of("material-image")); material.setTemplateFileId("template-2");
        assertThrows(org.hswebframework.web.exception.BusinessException.class,
            () -> DecorationDocuments.freeze(input, home, definitions, ctx, true));
    }
}
