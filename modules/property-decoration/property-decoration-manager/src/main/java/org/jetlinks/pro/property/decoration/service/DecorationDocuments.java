package org.jetlinks.pro.property.decoration.service;

import org.jetlinks.pro.property.decoration.model.DecorationModels;
import org.jetlinks.pro.property.decoration.model.DecorationRequests;
import org.jetlinks.pro.property.base.house.PropertyHouseholdInfo;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.jetlinks.pro.property.decoration.service.DecorationRules.require;

/** 四类签署正文在创建事务中按权威房屋、协议和申请信息重建，不接受任意客户端正文快照。 */
public final class DecorationDocuments {
    private DecorationDocuments() {}
    public static final Map<String,String> TITLES = Map.of("selfInspection","装修自检表","notices","装修须知","application","装修申请表","fire","消防安全责任书");
    static String content(String kind, DecorationRequests.Create input, PropertyHouseholdInfo home, DecorationModels.Agreement agreement) {
        String common=String.join("\n","房屋："+home.spaceName(),"业主："+home.customerName(),"联系电话："+input.getContactPhone(),
            "施工单位："+input.getConstructionUnit(),"施工负责人："+input.getConstructionManager(),"负责人电话："+input.getManagerPhone());
        var paragraphs=new java.util.ArrayList<String>();paragraphs.add(agreement.getTitle());paragraphs.add(agreement.getContent());
        agreement.getSupplements().forEach(s->paragraphs.add(s.getName()+"\n"+s.getContent()));
        String terms=String.join("\n",paragraphs);
        String body=switch(kind) {
            case "selfInspection" -> "自检结果："+(input.getSelfInspectionStatus()==1?"有问题":"无问题")+"\n自检说明："+empty(input.getSelfInspectionRemark());
            case "application" -> String.join("\n","装修类型："+input.getDecorationType(),"施工类型："+input.getConstructionType(),
                "开工日期："+date(input.getStartTime()),"计划竣工日期："+date(input.getCompletionTime()),"业主证件："+empty(input.getOwnerCertificate()),
                "负责人证件："+empty(input.getManagerCertificate()),"协议保证金："+agreement.getDeposit().setScale(2).toPlainString()+" 元");
            case "fire" -> "本人已阅读本项目装修协议及补充条款中的消防安全要求，承诺按约定办理装修并履行消防安全责任。\n"+terms;
            default -> terms;
        };
        return TITLES.get(kind)+"\n"+common+"\n\n"+body;
    }
    static List<DecorationModels.SignedDocument> freeze(DecorationRequests.Create input, PropertyHouseholdInfo home, DecorationModels.Agreement agreement, DecorationScope.Context ctx) {
        var documents=input.getSignedDocuments();
        require(documents.size()==4 && documents.stream().map(DecorationRequests.SignedDocument::getKind).collect(java.util.stream.Collectors.toSet()).equals(TITLES.keySet()),"signed_documents_required");
        require(documents.stream().map(DecorationRequests.SignedDocument::getDocumentFileId).distinct().count()==4,"invalid_signed_document");
        long time=System.currentTimeMillis();
        return documents.stream().map(d->{
            String content=content(d.getKind(),input,home,agreement);
            require(content.equals(d.getContent()) && d.getSignatureFileId()!=null && !d.getSignatureFileId().isBlank()
                && d.getDocumentFileId()!=null && !d.getDocumentFileId().isBlank() && !d.getDocumentFileId().equals(d.getSignatureFileId()),"invalid_signed_document");
            var result=new DecorationModels.SignedDocument();result.setKind(d.getKind());result.setTitle(TITLES.get(d.getKind()));result.setContent(content);
            result.setSignatureFileId(d.getSignatureFileId());result.setDocumentFileId(d.getDocumentFileId());result.setSignedBy(ctx.userId());result.setSignedName(ctx.userName());result.setSignedTime(time);
            return result;
        }).toList();
    }
    private static String empty(String text){return text==null?"":text;}
    // 文书沿用装修业务的上海自然日，与页面选择的施工日期保持一致。
    private static String date(Long time){return Instant.ofEpochMilli(time).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate().toString();}
}
