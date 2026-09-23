package kz.uitech;

import static org.junit.jupiter.api.Assertions.*;
import static kz.uitech.Model.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;

@EnabledIfEnvironmentVariable(named="UITECH_TEST_DB_URL",matches=".+")
class PostgresRepositoryTest {
    static final ObjectMapper MAPPER=CalculationTest.MAPPER.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static DriverManagerDataSource source;static JdbcTemplate jdbc;static String schema;
    @BeforeAll static void prepare(){
        String url=System.getenv("UITECH_TEST_DB_URL");schema="uitech_test_"+UUID.randomUUID().toString().replace("-","");
        source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,System.getenv("UITECH_DB_USER"),System.getenv("UITECH_DB_PASSWORD"));
        jdbc=new JdbcTemplate(source);jdbc.execute("CREATE SCHEMA "+schema);
        Flyway flyway=Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).cleanDisabled(true).load();flyway.migrate();flyway.validate();
    }
    @AfterAll static void cleanup(){if(jdbc!=null&&schema!=null)jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
    static PostgresRepository repository(){return new PostgresRepository(MAPPER,jdbc,new DataSourceTransactionManager(source));}
    static Dataset dataset(String suffix)throws Exception {ObjectNode json=CalculationTest.fixture("01_baseline").withObject("dataset");json.put("name","PG test "+suffix);return MAPPER.treeToValue(json,Dataset.class);}
    static ObjectNode calculation(LocalStore store,String suffix)throws Exception {
        Dataset d=dataset(suffix);String id=store.putDataset(d).path("dataset_id").asText();ObjectNode c=CalculationTest.fixture("01_baseline").withObject("calculation");c.put("dataset_id",id);
        return store.addCalculation(new CalculationEngine(MAPPER).calculate(d,MAPPER.treeToValue(c,CalculationRequest.class)));
    }
    @Test void datasetRowsRoundTripWithStableIdsAndNoDuplicates()throws Exception {
        var repo=repository();var store=new LocalStore(MAPPER,repo);Dataset d=dataset("roundtrip");
        ObjectNode saved=store.putDataset(d);String id=saved.path("dataset_id").asText();
        assertFalse(saved.path("reused").asBoolean());assertTrue(store.putDataset(d).path("reused").asBoolean());
        assertEquals(MAPPER.valueToTree(d),MAPPER.valueToTree(repo.dataset(id)));
        assertEquals(d.sales().size(),jdbc.queryForObject("SELECT count(*) FROM sales WHERE dataset_id=?",Integer.class,id));
        assertEquals("000001_",repo.review(id).products().get(0).sku1c());
        assertEquals(id,new LocalStore(MAPPER,repository()).putDataset(repo.dataset(id)).path("dataset_id").asText());
    }
    @Test void correctionAuditAndUnknownPurchaseValuesSurviveDatabaseReload()throws Exception {
        var store=new LocalStore(MAPPER,repository());Dataset original=dataset("source corrections");String parent=store.putDataset(original).path("dataset_id").asText();
        var change=new ProductCorrection("Manager","Confirmed box size; MOQ still unknown",new PurchaseFacts("box",new java.math.BigDecimal("12"),"CRITICAL",null,null),null,new SupplierPolicy("SUP_A",9,7));
        String child=new SourceCorrections(store,MAPPER).correct(parent,"P1",change).path("dataset_id").asText();
        var reopened=new LocalStore(MAPPER,repository());Dataset loaded=reopened.dataset(child);
        assertEquals("box",loaded.products().get(0).purchaseUnit());assertNull(loaded.products().get(0).moqPurchaseQty());assertNull(loaded.products().get(0).packMultiplePurchaseQty());
        assertEquals(MAPPER.valueToTree(original),MAPPER.valueToTree(reopened.dataset(parent)));
        JsonNode audit=MAPPER.valueToTree(loaded.sources().get(loaded.sources().size()-1).correction());assertEquals(9,audit.path("supplier_policy").path("lead_time_days").asInt());assertEquals("Manager",audit.path("author").asText());
        assertEquals(child,LocalStore.datasetId(MAPPER,loaded));
        assertEquals(original.sales(),loaded.sales());assertEquals(original.inbound(),loaded.inbound());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM sales WHERE dataset_id=?",Integer.class,child));
        var stock=original.inventory().get(0);
        String grandchild=new SourceCorrections(reopened,MAPPER).correct(child,"P1",new ProductCorrection("Second manager","Counted stock",null,new StockFacts(stock.warehouseId(),stock.asOf(),new java.math.BigDecimal("70")),null)).path("dataset_id").asText();
        assertEquals(parent,jdbc.queryForObject("SELECT history_dataset_id FROM datasets WHERE dataset_id=?",String.class,grandchild));
        assertEquals(original.sales(),reopened.dataset(grandchild).sales());assertEquals("box",reopened.dataset(grandchild).products().get(0).purchaseUnit());
        assertEquals(70,reopened.dataset(grandchild).inventory().get(0).freeStockQty().intValue());
    }
    @Test void failedDatasetImportRollsBackHeaderAndAllPreviouslyInsertedRows()throws Exception {
        ObjectNode raw=MAPPER.valueToTree(dataset("rollback"));((ObjectNode)raw.withArray("sales").get(0)).put("product_id","unknown");Dataset d=MAPPER.treeToValue(raw,Dataset.class);
        String id="ds_"+"a".repeat(64);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->repository().insertDataset(id,d,LocalStore.datasetSummary(MAPPER,id,d,Instant.now()),Instant.now()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM datasets WHERE dataset_id=?",Integer.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM products WHERE dataset_id=?",Integer.class,id));
    }
    @Test void concurrentEditsHaveOneWinnerAndApprovalSurvivesReopenButNotAnotherEdit()throws Exception {
        LocalStore first=new LocalStore(MAPPER,repository()),second=new LocalStore(MAPPER,repository());ObjectNode c=calculation(first,"concurrency");String id=c.path("calculation_id").asText();
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{
            List<Future<Integer>> results=new ArrayList<>();
            for(var entry:List.of(Map.entry(first,160),Map.entry(second,170)))results.add(pool.submit(()->{
                start.await();try{entry.getKey().patch(id,"item_P1",MAPPER.createObjectNode().put("expected_revision",1).put("final_purchase_qty",entry.getValue()).put("reason","Concurrent manager edit"));return 200;}catch(ApiException e){return e.status;}
            }));start.countDown();List<Integer> statuses=new ArrayList<>();for(var result:results)statuses.add(result.get(15,TimeUnit.SECONDS));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);
        }finally{pool.shutdownNow();}
        first.approve(id,MAPPER.createObjectNode().put("expected_revision",2).put("approved_by","PG test manager"));
        assertEquals("APPROVED",new LocalStore(MAPPER,repository()).calculation(id).path("status").asText());assertTrue(second.csv(id,"SUP_A",2).contains("Concurrent manager edit"));
        second.patch(id,"item_P1",MAPPER.createObjectNode().put("expected_revision",2).put("final_purchase_qty",180).put("reason","New requirement"));
        assertEquals(409,assertThrows(ApiException.class,()->first.csv(id,"SUP_A",2)).status);
        assertEquals(4,jdbc.queryForObject("SELECT count(*) FROM calculation_events WHERE calculation_id=?",Integer.class,id));
    }
    @Test void localMigrationIsRepeatablePreservesIdsAndDoesNotOverwriteLaterDatabaseEdits()throws Exception {
        Path dir=Files.createTempDirectory("uitech-pg-migration-");LocalStore files=new LocalStore(MAPPER,dir.toString());ObjectNode c=calculation(files,"migration");String id=c.path("calculation_id").asText();
        files.approve(id,MAPPER.createObjectNode().put("expected_revision",1).put("approved_by","Before migration"));
        LocalDataMigration migration=new LocalDataMigration(MAPPER,repository(),dir.toString());migration.migrate(dir);
        LocalStore database=new LocalStore(MAPPER,repository());assertEquals("APPROVED",database.calculation(id).path("status").asText());
        database.patch(id,"item_P1",MAPPER.createObjectNode().put("expected_revision",1).put("final_purchase_qty",160).put("reason","After migration"));
        migration.migrate(dir);assertEquals(2,database.calculation(id).path("revision").asInt());assertEquals("DRAFT",database.calculation(id).path("status").asText());
        assertEquals("APPROVED",files.calculation(id).path("status").asText());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM migration_sources WHERE entity_id IN (?,?)",Integer.class,id,c.path("dataset_id").asText()));
    }
    @Test void legacyJsonTreeFormattingKeepsOriginalPublicIdAndRecordsSourceChecksum()throws Exception {
        Path dir=Files.createTempDirectory("uitech-pg-legacy-");Dataset data=dataset("legacy");
        String legacyId=LocalStore.datasetId(MAPPER,data);Path file=dir.resolve(legacyId+".json");MAPPER.writeValue(file.toFile(),MAPPER.valueToTree(data));
        String reserialized=LocalStore.datasetId(MAPPER,MAPPER.readValue(file.toFile(),Dataset.class));assertNotEquals(legacyId,reserialized);
        new LocalDataMigration(MAPPER,repository(),dir.toString()).migrate(dir);
        assertEquals(data.name(),repository().dataset(legacyId).name());
        assertEquals(reserialized,jdbc.queryForObject("SELECT reserialized_dataset_id FROM migration_sources WHERE entity_id=?",String.class,legacyId));
        assertEquals(64,jdbc.queryForObject("SELECT length(source_sha256) FROM migration_sources WHERE entity_id=?",Integer.class,legacyId));
    }
}
