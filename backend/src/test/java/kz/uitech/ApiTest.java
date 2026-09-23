package kz.uitech;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.context.ActiveProfiles("file")
class ApiTest {
    static final Path DATA=createDirectory();
    static Path createDirectory(){try{return Files.createTempDirectory("uitech-api-test-");}catch(Exception e){throw new RuntimeException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("uitech.data-dir",()->DATA.toString());r.add("uitech.files-dir",()->DATA.resolve("files").toString());}
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired LocalStore store;
    @Test void sourceCorrectionKeepsApprovedParentAndOriginalFilesAndValidatesInputs()throws Exception {
        ObjectNode fixture=CalculationTest.fixture("01_baseline");fixture.withObject("dataset").put("name","API source correction");
        String parent=postJson("/api/v1/datasets",fixture.get("dataset"),201).path("dataset_id").asText();
        fixture.withObject("calculation").put("dataset_id",parent);
        ObjectNode old=postJson("/api/v1/calculations",fixture.get("calculation"),201);
        postJson("/api/v1/calculations/"+old.path("calculation_id").asText()+"/approve",approval(1),200);
        var originalFile=new Model.SourceFile("sales","original.xlsx","ab/"+"a".repeat(64)+".bin","a".repeat(64),123);
        store.attachFiles(parent,java.util.List.of(originalFile));
        ObjectNode change=mapper.createObjectNode().put("author","Procurement manager").put("reason","Confirmed warehouse report");
        JsonNode stock=fixture.withObject("dataset").withArray("inventory").get(0);
        change.putObject("stock").put("warehouse_id",stock.path("warehouse_id").asText()).put("as_of",stock.path("as_of").asText()).put("free_stock_qty",70);
        String endpoint="/api/v1/datasets/"+parent+"/products/P1/corrections";
        ObjectNode invalid=change.deepCopy();invalid.put("author"," ");postJson(endpoint,invalid,422);
        invalid=change.deepCopy();invalid.withObject("stock").put("free_stock_qty",-1);postJson(endpoint,invalid,422);
        invalid=change.deepCopy();invalid.withObject("stock").put("warehouse_id","wrong");postJson(endpoint,invalid,422);
        invalid=change.deepCopy();invalid.putObject("purchase").put("purchase_unit","box").put("purchase_unit_factor",0);postJson(endpoint,invalid,422);
        postJson("/api/v1/datasets/"+parent+"/products/missing/corrections",change,404);
        String child=postJson(endpoint,change,201).path("dataset_id").asText();assertNotEquals(parent,child);
        assertEquals(java.util.List.of(originalFile),store.files(child));
        assertEquals(40,store.dataset(parent).inventory().get(0).freeStockQty().intValue());
        assertEquals(70,store.dataset(child).inventory().get(0).freeStockQty().intValue());
        JsonNode audit=mapper.valueToTree(store.dataset(child).sources().get(store.dataset(child).sources().size()-1).correction());
        assertEquals(parent,audit.path("parent_dataset_id").asText());assertEquals("Procurement manager",audit.path("author").asText());
        assertEquals(40,audit.path("before_stock").path("free_stock_qty").asInt());assertEquals(70,audit.path("after_stock").path("free_stock_qty").asInt());
        fixture.withObject("calculation").put("dataset_id",child);ObjectNode result=postJson("/api/v1/calculations",fixture.get("calculation"),201);
        assertEquals(120,result.withArray("items").get(0).path("recommended_purchase_qty").asInt());assertEquals("DRAFT",result.path("status").asText());
        assertEquals("APPROVED",store.calculation(old.path("calculation_id").asText()).path("status").asText());
        assertEquals(150,store.calculation(old.path("calculation_id").asText()).withArray("items").get(0).path("final_purchase_qty").asInt());
    }
    ObjectNode postJson(String url,JsonNode body,int status)throws Exception {return (ObjectNode)mapper.readTree(mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body))).andExpect(status().is(status)).andReturn().getResponse().getContentAsByteArray());}
    ObjectNode create(String fixture)throws Exception {
        ObjectNode f=CalculationTest.fixture(fixture);String ds=postJson("/api/v1/datasets",f.get("dataset"),201).path("dataset_id").asText();
        ObjectNode request=f.withObject("calculation");request.put("dataset_id",ds);return postJson("/api/v1/calculations",request,201);
    }
    ObjectNode approval(int revision){return mapper.createObjectNode().put("expected_revision",revision).put("approved_by","Test manager");}
    @Test void completeApprovalRevisionExportAndPersistenceFlow()throws Exception {
        ObjectNode c=create("01_baseline");String id=c.path("calculation_id").asText(),base="/api/v1/calculations/"+id;
        JsonNode catalog=mapper.readTree(mvc.perform(get("/api/v1/datasets")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertTrue(catalog.isArray());assertTrue(java.util.stream.StreamSupport.stream(catalog.spliterator(),false).anyMatch(d->d.path("dataset_id").equals(c.get("dataset_id"))));
        assertFalse(catalog.get(0).has("sales"));
        JsonNode review=mapper.readTree(mvc.perform(get("/api/v1/datasets/"+c.path("dataset_id").asText()+"/review")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertFalse(review.has("sales"));assertFalse(review.has("monthly_stock"));assertEquals("P1",review.path("products").get(0).path("product_id").asText());
        mvc.perform(get(base+"/export?supplier_id=SUP_A&format=csv&revision=1")).andExpect(status().isConflict());
        postJson(base+"/approve",approval(1),200);
        String csv=mvc.perform(get(base+"/export?supplier_id=SUP_A&format=csv&revision=1")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);assertTrue(csv.contains("\"150\";\"150\""));
        ObjectNode patch=mapper.createObjectNode().put("expected_revision",1).put("final_purchase_qty",160).put("reason","Manager adjustment");
        mvc.perform(patch(base+"/items/item_P1").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(patch))).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.status").value("DRAFT"));
        mvc.perform(get(base+"/export?supplier_id=SUP_A&format=csv&revision=1")).andExpect(status().isConflict());
        postJson(base+"/approve",approval(1),409);
        patch.put("expected_revision",2).put("final_purchase_qty",155);
        mvc.perform(patch(base+"/items/item_P1").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(patch))).andExpect(status().isUnprocessableEntity());
        postJson(base+"/approve",approval(2),200);
        LocalStore reopened=new LocalStore(mapper,DATA.toString());assertEquals("APPROVED",reopened.calculation(id).path("status").asText());assertTrue(reopened.csv(id,"SUP_A",2).contains("\"150\";\"160\""));
        assertEquals("000001_",reopened.dataset(c.path("dataset_id").asText()).products().get(0).sku1c());
        var original=reopened.dataset(c.path("dataset_id").asText());assertEquals("ds_"+LocalStore.hash(mapper.writeValueAsBytes(original)),c.path("dataset_id").asText());
        assertTrue(reopened.putDataset(original).path("reused").asBoolean());
    }
    @Test void missingStockCannotBeApproved()throws Exception {ObjectNode c=create("14_missing_stock");postJson("/api/v1/calculations/"+c.path("calculation_id").asText()+"/approve",approval(1),422);}
    @Test void invalidPayloadReturnsStructuredError()throws Exception {mvc.perform(post("/api/v1/datasets").contentType(MediaType.APPLICATION_JSON).content("{\"unexpected\":true}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").exists());}
    @Test void csvProtectsFormulaValues(){assertEquals("\"'=SUM(A1)\"",LocalStore.escape("=SUM(A1)"));assertEquals("\"a\"\"b;c\"",LocalStore.escape("a\"b;c"));}
    @Test void importRoutesIekAndRejectsMixedSuppliers()throws Exception {
        var files=IekImporterTest.files();var manifest=IekImporterTest.manifest(files.keySet());
        var request=multipart("/api/v1/datasets/import");request.param("manifest",mapper.writeValueAsString(manifest));
        for(var entry:files.entrySet())request.file(new org.springframework.mock.web.MockMultipartFile(entry.getKey(),entry.getValue().name(),"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",entry.getValue().bytes()));
        byte[] result=mvc.perform(request).andExpect(status().isCreated()).andExpect(jsonPath("$.counts.reported_inbound").value(3)).andExpect(jsonPath("$.counts.inbound").value(0)).andReturn().getResponse().getContentAsByteArray();
        String id=mapper.readTree(result).path("dataset_id").asText();
        JsonNode originals=mapper.readTree(mvc.perform(get("/api/v1/datasets/"+id+"/files")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());assertEquals(6,originals.size());
        JsonNode original=originals.get(0);String download="/api/v1/datasets/"+id+"/files/"+original.path("sha256").asText();
        byte[] downloaded=mvc.perform(get(download)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(files.get(original.path("part_name").asText()).bytes(),downloaded);
        mvc.perform(get("/api/v1/datasets/"+id+"/files/"+"0".repeat(64))).andExpect(status().isNotFound());
        ObjectNode mixed=mapper.valueToTree(manifest);((ObjectNode)mixed.withArray("files").get(0)).put("supplier_id","SYSTEME");
        mvc.perform(multipart("/api/v1/datasets/import").param("manifest",mapper.writeValueAsString(mixed))).andExpect(status().isUnprocessableEntity());
    }
}
