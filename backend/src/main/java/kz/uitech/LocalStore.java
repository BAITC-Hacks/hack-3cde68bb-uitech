package kz.uitech;

import static kz.uitech.Model.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.security.MessageDigest;
import java.math.BigDecimal;

@Service
public class LocalStore {
    private final ObjectMapper mapper;
    private final Path directory;
    private final Map<String,Dataset> datasets=new ConcurrentHashMap<>();
    private final Map<String,ObjectNode> calculations=new ConcurrentHashMap<>();
    public LocalStore(ObjectMapper mapper,@Value("${uitech.data-dir}") String directory)throws IOException {
        this.mapper=mapper;this.directory=Path.of(directory).toAbsolutePath().normalize();Files.createDirectories(this.directory);
    }
    public static String hash(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}
    }
    public synchronized ObjectNode putDataset(Dataset d) throws IOException {
        Validation.dataset(d);MessageDigest digest;
        try{digest=MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        try(var out=new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest)){mapper.writeValue(out,d);}
        String id="ds_"+HexFormat.of().formatHex(digest.digest());boolean exists=Files.exists(path(id));
        if(!exists)write(id,d);datasets.put(id,d);
        ObjectNode result=summary(id,d);result.put("reused",exists);return result;
    }
    public Dataset dataset(String id) {
        validateId(id,"ds_");
        return datasets.computeIfAbsent(id,k->{
            if(!Files.isRegularFile(path(k)))throw new ApiException(404,"NOT_FOUND","Объект не найден");
            try{return mapper.readValue(path(k).toFile(),Dataset.class);}catch(IOException e){throw new IllegalStateException(e);}
        });
    }
    public ObjectNode summary(String id) {return summary(id,dataset(id));}
    public ArrayNode listDatasets() throws IOException {
        List<ObjectNode> summaries=new ArrayList<>();
        try(var files=Files.list(directory)) {
            for(Path file:files.filter(p->p.getFileName().toString().matches("ds_[a-f0-9]{64}\\.json")).toList()) {
                String name=file.getFileName().toString();summaries.add(summary(name.substring(0,name.length()-5)));
            }
        }
        summaries.sort(Comparator.comparing((ObjectNode n)->n.path("created_at").asText()).reversed());
        ArrayNode result=mapper.createArrayNode();summaries.forEach(result::add);return result;
    }
    private ObjectNode summary(String id,Dataset d) {
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
        try{n.put("created_at",Files.getLastModifiedTime(path(id)).toInstant().toString());}catch(IOException e){throw new IllegalStateException(e);}
        return n;
    }
    public synchronized ObjectNode addCalculation(ObjectNode result) throws IOException {
        String id="calc_"+UUID.randomUUID().toString().replace("-","");result.put("calculation_id",id);result.put("created_at",OffsetDateTime.now().toString());write(id,result);calculations.put(id,result.deepCopy());return result.deepCopy();
    }
    public ObjectNode calculation(String id) {
        validateId(id,"calc_");return calculations.computeIfAbsent(id,k->(ObjectNode)read(k)).deepCopy();
    }
    public ObjectNode item(String id,String itemId) {return find(calculation(id),itemId).deepCopy();}
    private ObjectNode find(ObjectNode calculation,String itemId) {
        for(JsonNode node:calculation.withArray("items"))if(node.path("item_id").asText().equals(itemId))return (ObjectNode)node;
        throw new ApiException(404,"NOT_FOUND","Позиция не найдена");
    }
    public synchronized ObjectNode patch(String id,String itemId,JsonNode request)throws IOException {
        ObjectNode calc=calculation(id);revision(calc,request.path("expected_revision"));ObjectNode row=find(calc,itemId);
        if(row.path("status").asText().equals("NEEDS_INPUT"))throw ApiException.invalid("Сначала устраните нехватку данных позиции");
        Validation.require(request.has("final_purchase_qty"),"final_purchase_qty обязателен; null сбрасывает корректировку");
        if(request.get("final_purchase_qty").isNull()) {row.set("final_purchase_qty",row.get("recommended_purchase_qty"));row.putNull("override");}
        else {
            Validation.require(request.get("final_purchase_qty").isNumber(),"Количество должно быть числом");
            BigDecimal q=request.get("final_purchase_qty").decimalValue();Validation.number(q,"final_purchase_qty",true);
            String reason=request.path("reason").asText("");Validation.text(reason,"reason");
            Product p=dataset(calc.path("dataset_id").asText()).products().stream().filter(x->x.productId().equals(row.path("product_id").asText())).findFirst().orElseThrow();
            if(q.signum()>0) {
                Validation.require(!row.path("status").asText().equals("EXCLUDED_BY_POLICY"),"Закупка запрещена политикой категории");
                Validation.require(p.moqPurchaseQty()!=null&&p.packMultiplePurchaseQty()!=null&&q.compareTo(p.moqPurchaseQty())>=0&&q.remainder(p.packMultiplePurchaseQty()).signum()==0,"Количество нарушает MOQ/кратность");
            }
            row.put("final_purchase_qty",q);row.putObject("override").put("quantity",q).put("reason",reason).put("changed_at",OffsetDateTime.now().toString());
        }
        calc.put("revision",calc.path("revision").asInt()+1);calc.put("status","DRAFT");calc.putNull("approval");saveCalculation(id,calc);return calc.deepCopy();
    }
    public synchronized ObjectNode approve(String id,JsonNode request)throws IOException {
        ObjectNode calc=calculation(id);revision(calc,request.path("expected_revision"));String name=request.path("approved_by").asText("");Validation.text(name,"approved_by");
        Validation.require(calc.withArray("items").size()>0,"Нет позиций для утверждения");
        Set<String> ack=new HashSet<>();request.path("acknowledged_issue_codes").forEach(x->ack.add(x.asText()));
        for(JsonNode row:calc.withArray("items")) {
            Validation.require(!row.path("status").asText().equals("NEEDS_INPUT"),"Есть позиции с недостающими данными");
            for(JsonNode a:row.path("assumptions"))Validation.require(ack.contains(a.path("code").asText()),"Не подтверждено допущение "+a.path("code").asText());
            for(JsonNode w:row.path("warnings"))Validation.require(ack.contains(w.path("code").asText()),"Не подтверждено предупреждение "+w.path("code").asText());
        }
        calc.put("status","APPROVED");calc.putObject("approval").put("revision",calc.path("revision").asInt()).put("approved_by",name).put("approved_at",OffsetDateTime.now().toString());
        saveCalculation(id,calc);return calc.deepCopy();
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
    private void saveCalculation(String id,ObjectNode node)throws IOException {write(id,node);calculations.put(id,node.deepCopy());}
    private static void validateId(String id,String prefix){if(id==null||!id.matches(prefix+"[a-f0-9]{"+(prefix.equals("ds_")?64:32)+"}"))throw new ApiException(404,"NOT_FOUND","Неизвестный идентификатор");}
    private Path path(String id){return directory.resolve(id+".json");}
    private JsonNode read(String id) {
        if(!Files.isRegularFile(path(id)))throw new ApiException(404,"NOT_FOUND","Объект не найден");
        try{return mapper.readTree(path(id).toFile());}catch(IOException e){throw new IllegalStateException(e);}
    }
    private void write(String id,Object data)throws IOException {
        Path temp=Files.createTempFile(directory,"write-",".tmp");
        try {mapper.writeValue(temp.toFile(),data);try{Files.move(temp,path(id),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temp,path(id),StandardCopyOption.REPLACE_EXISTING);}}
        finally {Files.deleteIfExists(temp);}
    }
}
