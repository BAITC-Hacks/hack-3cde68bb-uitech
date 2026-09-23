package kz.uitech;

import static kz.uitech.Model.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import java.util.function.UnaryOperator;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository @Profile("postgres")
public class PostgresRepository implements StorageRepository {
    private static final Set<String> HISTORY_TABLES=Set.of("sales","inbound","sales_coverage","inbound_coverage","availability","seasonality_profiles","monthly_sales","monthly_stock","reported_inbound","reported_purchase_rules");
    private final ObjectMapper mapper;private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public PostgresRepository(ObjectMapper mapper,JdbcTemplate jdbc,PlatformTransactionManager manager){this.mapper=mapper;this.jdbc=jdbc;this.jdbc.setFetchSize(1000);this.tx=new TransactionTemplate(manager);}
    public String kind(){return "postgres";}
    void recordMigration(String id,String kind,String sha,String reserialized){jdbc.update("INSERT INTO migration_sources(entity_id,entity_kind,source_sha256,reserialized_dataset_id) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",id,kind,sha,reserialized);}
    public void checkHealth(){try{jdbc.queryForObject("SELECT 1",Integer.class);}catch(org.springframework.dao.DataAccessException e){throw new ApiException(503,"STORAGE_UNAVAILABLE","Нет подключения к PostgreSQL");}}
    public Product product(String id,String productId){List<Product> rows=jdbc.query("SELECT payload::text FROM products WHERE dataset_id=? AND product_id=?",(r,n)->parse(r.getString(1),Product.class),id,productId);if(rows.isEmpty())throw new ApiException(404,"NOT_FOUND","Товар не найден");return rows.get(0);}
    public boolean insertDatasetWithFiles(String id,Dataset d,ObjectNode summary,Instant created,List<SourceFile> files){
        return Boolean.TRUE.equals(tx.execute(status->{boolean added=insertDataset(id,d,summary,created);attachFiles(id,files);return added;}));
    }
    public boolean insertCorrectedDataset(String id,Dataset d,ObjectNode summary,Instant created,List<SourceFile> files,String parentId){
        return Boolean.TRUE.equals(tx.execute(status->{boolean added=insertDataset(id,d,summary,created,parentId);attachFiles(id,files);return added;}));
    }
    public void attachFiles(String id,List<SourceFile> files){tx.executeWithoutResult(status->{
        for(SourceFile f:files)jdbc.update("INSERT INTO source_files(dataset_id,part_name,file_name,object_key,sha256,byte_size) VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",id,f.partName(),f.fileName(),f.objectKey(),f.sha256(),f.byteSize());
    });}
    public List<SourceFile> files(String id){return jdbc.query("SELECT part_name,file_name,object_key,sha256,byte_size FROM source_files WHERE dataset_id=? ORDER BY part_name,sha256",(r,n)->new SourceFile(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5)),id);}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private <T>T parse(String value,Class<T> type){try{return mapper.readValue(value,type);}catch(Exception e){throw new IllegalStateException(e);}}
    private ObjectNode one(String sql,String id){
        List<ObjectNode> rows=jdbc.query(sql,(r,n)->parse(r.getString(1),ObjectNode.class),id);
        if(rows.isEmpty())throw new ApiException(404,"NOT_FOUND","Объект не найден");return rows.get(0);
    }
    public boolean insertDataset(String id,Dataset d,ObjectNode summary,Instant created){
        return insertDataset(id,d,summary,created,null);
    }
    private boolean insertDataset(String id,Dataset d,ObjectNode summary,Instant created,String parentId){
        return Boolean.TRUE.equals(tx.execute(status->{
            ObjectNode meta=mapper.createObjectNode().put("schema_version",d.schemaVersion()).put("name",d.name()).put("data_kind",d.dataKind()).put("timezone",d.timezone());
            String historyOwner=parentId==null?null:jdbc.queryForObject("SELECT COALESCE(history_dataset_id,dataset_id) FROM datasets WHERE dataset_id=?",String.class,parentId);
            int added=jdbc.update("INSERT INTO datasets(dataset_id,metadata,summary,created_at,history_dataset_id) VALUES (?,?::jsonb,?::jsonb,?,?) ON CONFLICT DO NOTHING",id,json(meta),json(summary),OffsetDateTime.ofInstant(created,ZoneOffset.UTC),historyOwner);
            if(added==0)return false;
            insertRows("suppliers","dataset_id",id,d.suppliers());insertRows("products","dataset_id",id,d.products());
            insertRows("inventory","dataset_id",id,d.inventory());insertRows("sources","dataset_id",id,d.sources());insertRows("issues","dataset_id",id,d.issues());
            if(historyOwner==null){
            insertRows("sales","dataset_id",id,d.sales());insertRows("inbound","dataset_id",id,d.inbound());
            insertRows("sales_coverage","dataset_id",id,d.salesCoverage());insertRows("inbound_coverage","dataset_id",id,d.inboundCoverage());insertRows("availability","dataset_id",id,d.availability());
            insertRows("seasonality_profiles","dataset_id",id,d.seasonalityProfiles());
            insertRows("monthly_sales","dataset_id",id,d.monthlySales());insertRows("monthly_stock","dataset_id",id,d.monthlyStock());
            insertRows("reported_inbound","dataset_id",id,d.reportedInbound());insertRows("reported_purchase_rules","dataset_id",id,d.reportedPurchaseRules());
            }
            return true;
        }));
    }
    private void insertRows(String table,String parent,String id,List<?> values){
        // Table/column names are hardcoded by callers; values are bound parameters.
        for(int offset=0;offset<values.size();offset+=500){
            List<Object[]> batch=new ArrayList<>();
            for(int i=offset;i<Math.min(offset+500,values.size());i++)batch.add(new Object[]{id,i,json(values.get(i))});
            jdbc.batchUpdate("INSERT INTO "+table+"("+parent+",position,payload) VALUES (?,?,?::jsonb)",batch);
        }
    }
    private <T>List<T> rows(String table,String id,Class<T> type){
        String owner=HISTORY_TABLES.contains(table)?"(SELECT COALESCE(history_dataset_id,dataset_id) FROM datasets WHERE dataset_id=?)":"?";
        return jdbc.query("SELECT payload::text FROM "+table+" WHERE dataset_id="+owner+" ORDER BY position",(r,n)->parse(r.getString(1),type),id);
    }
    public Dataset dataset(String id){
        return tx.execute(status->readDataset(id));
    }
    private Dataset readDataset(String id){
        ObjectNode m=one("SELECT metadata::text FROM datasets WHERE dataset_id=?",id);
        return new Dataset(m.path("schema_version").asText(),m.path("name").asText(),m.path("data_kind").asText(),m.path("timezone").asText(),
                rows("suppliers",id,Supplier.class),rows("products",id,Product.class),rows("sales_coverage",id,Coverage.class),rows("sales",id,Sale.class),
                rows("inventory",id,Inventory.class),rows("inbound",id,Inbound.class),rows("inbound_coverage",id,InboundCoverage.class),rows("availability",id,Availability.class),
                rows("seasonality_profiles",id,Seasonality.class),rows("sources",id,Source.class),rows("issues",id,Issue.class),
                rows("monthly_sales",id,MonthlyValue.class),rows("monthly_stock",id,MonthlyValue.class),rows("reported_inbound",id,ReportedInbound.class),rows("reported_purchase_rules",id,ReportedPurchaseRule.class));
    }
    public DatasetReview review(String id){
        ObjectNode m=one("SELECT metadata::text FROM datasets WHERE dataset_id=?",id);
        return new DatasetReview(m.path("schema_version").asText(),m.path("name").asText(),m.path("data_kind").asText(),m.path("timezone").asText(),
                rows("suppliers",id,Supplier.class),rows("products",id,Product.class),rows("inventory",id,Inventory.class),rows("sales_coverage",id,Coverage.class),
                rows("sources",id,Source.class),rows("issues",id,Issue.class),rows("reported_inbound",id,ReportedInbound.class),rows("reported_purchase_rules",id,ReportedPurchaseRule.class));
    }
    public ObjectNode summary(String id){return one("SELECT summary::text FROM datasets WHERE dataset_id=?",id);}
    public ArrayNode summaries(){ArrayNode list=mapper.createArrayNode();jdbc.query("SELECT summary::text FROM datasets ORDER BY created_at DESC,dataset_id",(org.springframework.jdbc.core.RowCallbackHandler)r->list.add(parse(r.getString(1),ObjectNode.class)));return list;}
    private ObjectNode header(ObjectNode c){ObjectNode header=c.deepCopy();header.remove("items");return header;}
    public boolean insertCalculation(ObjectNode c){return insertCalculation(c,"CREATED");}
    boolean insertCalculation(ObjectNode c,String action){
        return Boolean.TRUE.equals(tx.execute(status->{
            String id=c.path("calculation_id").asText();
            int added=jdbc.update("INSERT INTO calculations(calculation_id,dataset_id,revision,status,payload,created_at) VALUES (?,?,?,?,?::jsonb,?) ON CONFLICT DO NOTHING",id,c.path("dataset_id").asText(),c.path("revision").asInt(),c.path("status").asText(),json(header(c)),OffsetDateTime.parse(c.path("created_at").asText()));
            if(added==0)return false;
            insertRows("calculation_items","calculation_id",id,items(c));
            event(c,action,mapper.createObjectNode().put("status",c.path("status").asText()).put("item_count",c.withArray("items").size()));return true;
        }));
    }
    private List<JsonNode> items(ObjectNode c){List<JsonNode> items=new ArrayList<>();c.withArray("items").forEach(items::add);return items;}
    private ObjectNode loadCalculation(String id,boolean update){
        ObjectNode result=one("SELECT payload::text FROM calculations WHERE calculation_id=? FOR "+(update?"UPDATE":"SHARE"),id);
        ArrayNode items=result.putArray("items");jdbc.query("SELECT payload::text FROM calculation_items WHERE calculation_id=? ORDER BY position",(org.springframework.jdbc.core.RowCallbackHandler)r->items.add(parse(r.getString(1),ObjectNode.class)),id);return result;
    }
    public ObjectNode calculation(String id){return tx.execute(status->loadCalculation(id,false));}
    public ObjectNode mutateCalculation(String id,UnaryOperator<ObjectNode> mutation){
        return tx.execute(status->{
            ObjectNode before=loadCalculation(id,true),after=mutation.apply(before.deepCopy());
            jdbc.update("UPDATE calculations SET revision=?,status=?,payload=?::jsonb,updated_at=now() WHERE calculation_id=?",after.path("revision").asInt(),after.path("status").asText(),json(header(after)),id);
            ObjectNode details=mapper.createObjectNode().put("previous_revision",before.path("revision").asInt());
            details.set("previous_approval",before.path("approval"));details.set("approval",after.path("approval"));ArrayNode changes=details.putArray("changes");
            for(int i=0;i<after.withArray("items").size();i++){
                JsonNode old=before.withArray("items").get(i),row=after.withArray("items").get(i);
                if(!old.equals(row)){
                    jdbc.update("UPDATE calculation_items SET payload=?::jsonb WHERE calculation_id=? AND position=?",json(row),id,i);
                    ObjectNode change=changes.addObject().put("item_id",row.path("item_id").asText());change.set("previous_quantity",old.path("final_purchase_qty"));change.set("quantity",row.path("final_purchase_qty"));change.set("override",row.path("override"));
                }
            }
            event(after,"APPROVED".equals(after.path("status").asText())?"APPROVED":"PATCHED",details);return after;
        });
    }
    private void event(ObjectNode c,String action,ObjectNode details){jdbc.update("INSERT INTO calculation_events(calculation_id,revision,action,details) VALUES (?,?,?,?::jsonb)",c.path("calculation_id").asText(),c.path("revision").asInt(),action,json(details));}
}
