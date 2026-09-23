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
class ApiTest {
    static final Path DATA=createDirectory();
    static Path createDirectory(){try{return Files.createTempDirectory("uitech-api-test-");}catch(Exception e){throw new RuntimeException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("uitech.data-dir",()->DATA.toString());}
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    ObjectNode postJson(String url,JsonNode body,int status)throws Exception {return (ObjectNode)mapper.readTree(mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body))).andExpect(status().is(status)).andReturn().getResponse().getContentAsByteArray());}
    ObjectNode create(String fixture)throws Exception {
        ObjectNode f=CalculationTest.fixture(fixture);String ds=postJson("/api/v1/datasets",f.get("dataset"),201).path("dataset_id").asText();
        ObjectNode request=f.withObject("calculation");request.put("dataset_id",ds);return postJson("/api/v1/calculations",request,201);
    }
    ObjectNode approval(int revision){return mapper.createObjectNode().put("expected_revision",revision).put("approved_by","Test manager");}
    @Test void completeApprovalRevisionExportAndPersistenceFlow()throws Exception {
        ObjectNode c=create("01_baseline");String id=c.path("calculation_id").asText(),base="/api/v1/calculations/"+id;
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
    }
    @Test void missingStockCannotBeApproved()throws Exception {ObjectNode c=create("14_missing_stock");postJson("/api/v1/calculations/"+c.path("calculation_id").asText()+"/approve",approval(1),422);}
    @Test void invalidPayloadReturnsStructuredError()throws Exception {mvc.perform(post("/api/v1/datasets").contentType(MediaType.APPLICATION_JSON).content("{\"unexpected\":true}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").exists());}
    @Test void csvProtectsFormulaValues(){assertEquals("\"'=SUM(A1)\"",LocalStore.escape("=SUM(A1)"));assertEquals("\"a\"\"b;c\"",LocalStore.escape("a\"b;c"));}
}
