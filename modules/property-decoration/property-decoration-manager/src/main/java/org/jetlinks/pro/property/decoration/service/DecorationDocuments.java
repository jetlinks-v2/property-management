package org.jetlinks.pro.property.decoration.service;

import org.jetlinks.pro.property.decoration.model.DecorationModels;
import org.jetlinks.pro.property.decoration.model.DecorationRequests;
import org.jetlinks.pro.property.base.house.PropertyHouseholdInfo;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import static org.jetlinks.pro.property.decoration.service.DecorationRules.require;

/** PC核验线下签字文件；业主端按实际启用资料逐项签署，正文绑定本次申请和冻结模板。 */
public final class DecorationDocuments {
    private DecorationDocuments() {}
    public static final Map<String,String> TITLES = Map.of("selfInspection","装修自检表");
    static String content(String kind, DecorationRequests.Create input, PropertyHouseholdInfo home, DecorationModels.Agreement agreement) {
        return content(kind, input, home, List.of());
    }
    static String content(String kind, DecorationRequests.Create input, PropertyHouseholdInfo home, List<DecorationModels.MaterialDefinition> definitions) {
        if ("selfInspection".equals(kind)) return DecorationSelfInspectionForm.content(input, home);
        var definition = definitions.stream().filter(item -> item.isEnabled() && "material".equals(item.getKind()) && kind.equals("material:" + item.getId())).findFirst();
        require(definition.isPresent(), "invalid_signed_document");
        var item = definition.orElseThrow();
        return String.join("\n", item.getName(), "房屋：" + home.spaceName(), "业主：" + home.customerName(), "联系电话：" + input.getContactPhone(),
            "施工单位：" + input.getConstructionUnit(), "施工负责人：" + input.getConstructionManager(), "负责人电话：" + input.getManagerPhone(),
            "装修类型：" + input.getDecorationType(), "施工类型：" + input.getConstructionType(), "开工日期：" + date(input.getStartTime()),
            "计划竣工日期：" + date(input.getCompletionTime()), "业主证件：" + empty(input.getOwnerCertificate()),
            "负责人证件：" + empty(input.getManagerCertificate()), "资料模板：" + empty(item.getTemplateFileId()));
    }
    static List<DecorationModels.SignedDocument> freeze(DecorationRequests.Create input, PropertyHouseholdInfo home,
            List<DecorationModels.MaterialDefinition> definitions, DecorationScope.Context ctx, boolean mine) {
        var documents = input.getSignedDocuments();
        if (!mine) {
            require(!input.getSelfInspectionFiles().isEmpty(), "self_inspection_file_required");
            require(documents.isEmpty(), "invalid_signed_document");
            return List.of();
        }
        // 必填资料由材料配置校验；所有已提交的业主资料必须有对应整页签字图片，图纸仍按上传处理。
        var expected = definitions.stream().filter(item -> item.isEnabled() && "material".equals(item.getKind())
            && input.getMaterials().stream().anyMatch(upload -> upload.getItemId().equals(item.getId()) && !upload.getFileIds().isEmpty()))
            .map(item -> "material:" + item.getId()).collect(Collectors.toCollection(java.util.HashSet::new));
        expected.add("selfInspection");
        require(documents.size() == expected.size() && documents.stream().map(DecorationRequests.SignedDocument::getKind).collect(Collectors.toSet()).equals(expected), "signed_documents_required");
        require(documents.stream().map(DecorationRequests.SignedDocument::getDocumentFileId).distinct().count() == documents.size(), "invalid_signed_document");
        long time = System.currentTimeMillis();
        return documents.stream().map(document -> {
            String content = content(document.getKind(), input, home, definitions);
            require(content.equals(document.getContent()) && document.getSignatureFileId() != null && !document.getSignatureFileId().isBlank()
                && document.getDocumentFileId() != null && !document.getDocumentFileId().isBlank()
                && !document.getDocumentFileId().equals(document.getSignatureFileId()), "invalid_signed_document");
            if (!"selfInspection".equals(document.getKind())) {
                require(input.getMaterials().stream().anyMatch(upload -> document.getKind().equals("material:" + upload.getItemId())
                    && upload.getFileIds().equals(List.of(document.getDocumentFileId()))), "invalid_signed_document");
            }
            var result = new DecorationModels.SignedDocument(); result.setKind(document.getKind());
            result.setTitle("selfInspection".equals(document.getKind()) ? TITLES.get("selfInspection")
                : definitions.stream().filter(item -> document.getKind().equals("material:" + item.getId())).findFirst().orElseThrow().getName());
            result.setContent(content); result.setSignatureFileId(document.getSignatureFileId()); result.setDocumentFileId(document.getDocumentFileId());
            result.setSignedBy(ctx.userId()); result.setSignedName(ctx.userName()); result.setSignedTime(time);
            return result;
        }).toList();
    }
    private static String empty(String text) { return text == null ? "" : text; }
    private static String date(Long time) { return Instant.ofEpochMilli(time).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate().toString(); }
}
