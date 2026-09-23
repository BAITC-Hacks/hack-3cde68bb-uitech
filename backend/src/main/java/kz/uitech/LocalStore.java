package kz.uitech;

import static kz.uitech.Model.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.*;
import java.security.MessageDigest;
import java.math.BigDecimal;

@Service
public class LocalStore {
    private final ObjectMapper mapper;
    private final StorageRepository repository;
    @org.springframework.beans.factory.annotation.Autowired
    public LocalStore(ObjectMapper mapper,StorageRepository repository){this.mapper=mapper;this.repository=repository;}
    public LocalStore(ObjectMapper mapper,String directory){this(mapper,new FileRepository(mapper,directory));}
    public String storageKind(){return repository.kind();}
    public void checkHealth(){repository.checkHealth();}
    public void attachFiles(String id,List<SourceFile> files){validateId(id,"ds_");repository.attachFiles(id,files);}
    public List<SourceFile> files(String id){summary(id);return repository.files(id);}
    public DatasetReview review(String id){validateId(id,"ds_");return repository.review(id);}
    public static String hash(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}
    }
    public synchronized ObjectNode putDataset(Dataset d) throws IOException {
        return putDataset(d,List.of());
    }
    public synchronized ObjectNode putDataset(Dataset d,List<SourceFile> files) throws IOException {
        Validation.dataset(d);String id=datasetId(mapper,d);
        java.time.Instant created=java.time.Instant.now();
        boolean inserted=repository.insertDatasetWithFiles(id,d,datasetSummary(mapper,id,d,created),created,files);
        ObjectNode result=repository.summary(id);result.put("reused",!inserted);return result;
    }
    synchronized ObjectNode putCorrectedDataset(Dataset d,String parentId)throws IOException {
        Validation.dataset(d);String id=datasetId(mapper,d);var created=java.time.Instant.now();
        boolean inserted=repository.insertCorrectedDataset(id,d,datasetSummary(mapper,id,d,created),created,files(parentId),parentId);
        ObjectNode result=repository.summary(id);result.put("reused",!inserted);return result;
    }
    static String datasetId(ObjectMapper mapper,Dataset d)throws IOException{
        MessageDigest digest;
        try{digest=MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        try(var out=new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest)){mapper.writeValue(out,d);}
        return "ds_"+HexFormat.of().formatHex(digest.digest());
    }
    public Dataset dataset(String id){validateId(id,"ds_");return repository.dataset(id);}
    public ObjectNode summary(String id){validateId(id,"ds_");return repository.summary(id);}
    public ArrayNode listDatasets(){return repository.summaries();}
    static ObjectNode datasetSummary(ObjectMapper mapper,String id,Dataset d,java.time.Instant createdAt) {
        ObjectNode n=mapper.createObjectNode();n.put("dataset_id",id);n.put("name",d.name());n.put("data_kind",d.dataKind());n.put("schema_version",d.schemaVersion());
        boolean partial=d.issues().stream().anyMatch(i->!"info".equals(i.severity())) || d.salesCoverage().stream().anyMatch(c->!c.complete()) || d.products().stream().anyMatch(p->p.categoryId()==null||p.moqPurchaseQty()==null||p.packMultiplePurchaseQty()==null);
        if(d.availability().isEmpty()||d.inventory().isEmpty())partial=true;
        n.put("status",partial?"PARTIAL":"READY");
        n.putObject("counts").put("suppliers",d.suppliers().size()).put("products",d.products().size()).put("sales",d.sales().size()).put("inventory",d.inventory().size()).put("inbound",d.inbound().size()).put("monthly_sales",d.monthlySales().size()).put("monthly_stock",d.monthlyStock().size());
        ((ObjectNode)n.get("counts")).put("reported_inbound",d.reportedInbound().size()).put("reported_purchase_rules",d.reportedPurchaseRules().size());
        n.set("sources",mapper.valueToTree(d.sources()));n.set("issues",mapper.valueToTree(d.issues().stream().limit(200).toList()));n.put("issue_count",d.issues().size());n.put("issues_truncated",d.issues().size()>200);
        ObjectNode coverage=n.putObject("coverage");coverage.set("sales",mapper.valueToTree(d.salesCoverage()));coverage.set("inbound",mapper.valueToTree(d.inboundCoverage()));
        coverage.set("inventory_dates",mapper.valueToTree(d.inventory().stream().map(Inventory::asOf).distinct().sorted().toList()));
        coverage.put("availability_intervals",d.availability().size());coverage.put("sales_without_customer_id",d.sales().stream().filter(s->s.customerId()==null).count());
        n.put("created_at",createdAt.toString());
        return n;
    }
    public synchronized ObjectNode addCalculation(ObjectNode result) throws IOException {
        String id="calc_"+UUID.randomUUID().toString().replace("-","");result.put("calculation_id",id);result.put("created_at",OffsetDateTime.now().toString());repository.insertCalculation(result);return result.deepCopy();
    }
    public ObjectNode calculation(String id) {
        validateId(id,"calc_");return repository.calculation(id);
    }
    public ObjectNode item(String id,String itemId) {return find(calculation(id),itemId).deepCopy();}
    private ObjectNode find(ObjectNode calculation,String itemId) {
        for(JsonNode node:calculation.withArray("items"))if(node.path("item_id").asText().equals(itemId))return (ObjectNode)node;
        throw new ApiException(404,"NOT_FOUND","Позиция не найдена");
    }
    public synchronized ObjectNode patch(String id,String itemId,JsonNode request)throws IOException {
        validateId(id,"calc_");return repository.mutateCalculation(id,calc->patchDraft(calc,itemId,request));
    }
    private ObjectNode patchDraft(ObjectNode calc,String itemId,JsonNode request){
        revision(calc,request.path("expected_revision"));ObjectNode row=find(calc,itemId);
        if(row.path("status").asText().equals("NEEDS_INPUT"))throw ApiException.invalid("Сначала устраните нехватку данных позиции");
        Validation.require(request.has("final_purchase_qty"),"final_purchase_qty обязателен; null сбрасывает корректировку");
        if(request.get("final_purchase_qty").isNull()) {row.set("final_purchase_qty",row.get("recommended_purchase_qty"));row.putNull("override");}
        else {
            Validation.require(request.get("final_purchase_qty").isNumber(),"Количество должно быть числом");
            BigDecimal q=request.get("final_purchase_qty").decimalValue();Validation.number(q,"final_purchase_qty",true);
            String reason=request.path("reason").asText("");Validation.text(reason,"reason");
            Product p=repository.product(calc.path("dataset_id").asText(),row.path("product_id").asText());
            if(q.signum()>0) {
                Validation.require(!row.path("status").asText().equals("EXCLUDED_BY_POLICY"),"Закупка запрещена политикой категории");
                Validation.require(p.moqPurchaseQty()!=null&&p.packMultiplePurchaseQty()!=null&&q.compareTo(p.moqPurchaseQty())>=0&&q.remainder(p.packMultiplePurchaseQty()).signum()==0,"Количество нарушает MOQ/кратность");
            }
            row.put("final_purchase_qty",q);row.putObject("override").put("quantity",q).put("reason",reason).put("changed_at",OffsetDateTime.now().toString());
        }
        calc.put("revision",calc.path("revision").asInt()+1);calc.put("status","DRAFT");calc.putNull("approval");return calc;
    }
    public synchronized ObjectNode approve(String id,JsonNode request)throws IOException {
        validateId(id,"calc_");return repository.mutateCalculation(id,calc->approveDraft(calc,request));
    }
    private ObjectNode approveDraft(ObjectNode calc,JsonNode request){
        revision(calc,request.path("expected_revision"));String name=request.path("approved_by").asText("");Validation.text(name,"approved_by");
        Validation.require(calc.withArray("items").size()>0,"Нет позиций для утверждения");
        Set<String> ack=new HashSet<>();request.path("acknowledged_issue_codes").forEach(x->ack.add(x.asText()));
        for(JsonNode row:calc.withArray("items")) {
            Validation.require(!row.path("status").asText().equals("NEEDS_INPUT"),"Есть позиции с недостающими данными");
            for(JsonNode a:row.path("assumptions"))Validation.require(ack.contains(a.path("code").asText()),"Не подтверждено допущение "+a.path("code").asText());
            for(JsonNode w:row.path("warnings"))Validation.require(ack.contains(w.path("code").asText()),"Не подтверждено предупреждение "+w.path("code").asText());
        }
        calc.put("status","APPROVED");calc.putObject("approval").put("revision",calc.path("revision").asInt()).put("approved_by",name).put("approved_at",OffsetDateTime.now().toString());
        return calc;
    }
    public synchronized String csv(String id,String supplier,int revision) {
        ObjectNode calc=calculation(id);
        if(!calc.path("status").asText().equals("APPROVED")||calc.path("revision").asInt()!=revision||calc.path("approval").path("revision").asInt()!=revision)throw new ApiException(409,"APPROVAL_REQUIRED","Нужна актуальная утверждённая версия");
        boolean found=false;StringBuilder csv=new StringBuilder("\uFEFFsupplier_id;sku_1c;supplier_article;name;warehouse_id;as_of;purchase_unit;recommended_purchase_qty;final_purchase_qty;reason\r\n");
        for(JsonNode r:calc.withArray("items"))if(r.path("supplier_id").asText().equals(supplier)) {
            found=true;if(r.path("final_purchase_qty").decimalValue().signum()<=0)continue;
            List<String> cells=List.of(text(r,"supplier_id"),text(r,"sku_1c"),text(r,"supplier_article"),text(r,"name"),text(calc,"warehouse_id"),text(calc,"as_of"),text(r,"purchase_unit"),r.path("recommended_purchase_qty").decimalValue().toPlainString(),r.path("final_purchase_qty").decimalValue().toPlainString(),r.path("override").isObject()?r.path("override").path("reason").asText():r.path("explanation").asText());
            csv.append(cells.stream().map(LocalStore::escape).collect(java.util.stream.Collectors.joining(";"))).append("\r\n");
        }
        if(!found)throw new ApiException(404,"NOT_FOUND","Поставщик не найден в расчёте");return csv.toString();
    }
    private static String text(JsonNode n,String k){return n.path(k).isNull()?"":n.path(k).asText();}
    static String escape(String value) {
        String s=value;String trimmed=s.stripLeading();if(!trimmed.isEmpty()&&"=+-@\t\r\n".indexOf(trimmed.charAt(0))>=0)s="'"+s;
        return "\""+s.replace("\"","\"\"")+"\"";
    }
    private void revision(ObjectNode calc,JsonNode revision) {if(!revision.isIntegralNumber()||!revision.canConvertToInt()||revision.intValue()!=calc.path("revision").asInt())throw new ApiException(409,"REVISION_CONFLICT","Версия расчёта изменилась");}
    static void validateId(String id,String prefix){if(id==null||!id.matches(prefix+"[a-f0-9]{"+(prefix.equals("ds_")?64:32)+"}"))throw new ApiException(404,"NOT_FOUND","Неизвестный идентификатор");}
}
