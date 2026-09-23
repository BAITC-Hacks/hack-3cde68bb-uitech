package kz.uitech;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

class CalculationTest {
    static final ObjectMapper MAPPER=JsonMapper.builder().addModule(new JavaTimeModule()).propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    static final Path FIXTURES=Path.of("../fixtures/contract");
    static ObjectNode fixture(String name)throws Exception {return (ObjectNode)MAPPER.readTree(FIXTURES.resolve(name+".json").toFile());}
    static ObjectNode calculate(ObjectNode fixture)throws Exception {
        Model.Dataset d=MAPPER.treeToValue(fixture.get("dataset"),Model.Dataset.class);Validation.dataset(d);
        ObjectNode request=fixture.withObject("calculation").deepCopy();request.put("dataset_id","ds_test");
        return new CalculationEngine(MAPPER).calculate(d,MAPPER.treeToValue(request,Model.CalculationRequest.class));
    }
    @TestFactory Stream<DynamicTest> acceptanceFixtures()throws Exception {
        List<Path> files;try(var paths=Files.list(FIXTURES)){files=paths.filter(p->p.toString().endsWith(".json")).sorted().toList();}
        assertEquals(17,files.size());
        return files.stream().map(path->DynamicTest.dynamicTest(path.getFileName().toString(),()->{
            ObjectNode f=(ObjectNode)MAPPER.readTree(path.toFile()),result=calculate(f);JsonNode expected=f.get("expected");
            if(expected.has("supplier_groups")) {
                assertEquals(expected.get("supplier_groups").size(),result.get("supplier_groups").size());
                for(JsonNode group:result.get("supplier_groups")) {
                    Set<String> actual=new HashSet<>();for(JsonNode item:result.get("items"))if(item.get("supplier_id").equals(group.get("supplier_id")))actual.add(item.get("product_id").asText());
                    Set<String> wanted=new HashSet<>();expected.path("supplier_groups").path(group.get("supplier_id").asText()).forEach(n->wanted.add(n.asText()));assertEquals(wanted,actual);
                }
                for(JsonNode item:result.get("items")){decimal(expected.path("recommended_purchase_qty_by_product").path(item.path("product_id").asText()),item.path("recommended_purchase_qty"));assertFalse(item.path("explanation").asText().isBlank());}
                return;
            }
            JsonNode row=result.path("items").get(0);assertEquals(expected.path("product_id"),row.path("product_id"));
            if(!expected.has("status"))assertEquals("READY",row.path("status").asText(),row.toPrettyString());
            for(String field:List.of("status","base_daily_rate","forecast_horizon_qty","safety_stock_qty","raw_order_stock_qty","recommended_purchase_qty","recommended_stock_qty","first_stockout_date","lost_demand_estimate_qty","eligible_inbound_qty"))if(expected.has(field)) {
                JsonNode actual=row.has(field)?row.get(field):row.path("factors").path(field),wanted=expected.get(field);
                if(wanted.isNumber())decimal(wanted,actual);else assertEquals(wanted,actual,field);
            }
            Set<String> excluded=new HashSet<>();row.path("anomalies").forEach(a->a.path("document_ids").forEach(n->excluded.add(n.asText())));
            if(expected.has("excluded_document_ids")){Set<String> wanted=new HashSet<>();expected.get("excluded_document_ids").forEach(n->wanted.add(n.asText()));assertEquals(wanted,excluded);}
            expected.path("preserve_document_ids").forEach(n->assertFalse(excluded.contains(n.asText())));
            if(expected.has("anomalous_customer_id"))assertTrue(StreamSupport.stream(row.path("anomalies").spliterator(),false).anyMatch(a->a.path("customer_id").equals(expected.get("anomalous_customer_id"))));
            if(expected.has("compare_to")) {
                double baseline=calculate(fixture(expected.get("compare_to").asText())).path("items").get(0).path("factors").path("forecast_horizon_qty").asDouble();
                assertTrue(Math.abs(row.path("factors").path("forecast_horizon_qty").asDouble()/baseline-1)<=expected.path("max_regular_forecast_relative_change").asDouble());
                assertEquals(10,row.path("history").get(14).path("regular_sales_qty").asInt());
            }
            if(path.getFileName().toString().startsWith("10_")){double daily=row.path("factors").path("forecast_horizon_qty").asDouble()/14;assertTrue(daily>=10);assertTrue(daily>row.path("factors").path("base_daily_rate").asDouble());}
            if(path.getFileName().toString().startsWith("16_"))assertEquals(0,row.path("factors").path("excluded_sales_qty").asInt());
        }));
    }
    static void decimal(JsonNode expected,JsonNode actual){assertTrue(actual.isNumber(),actual.toString());assertTrue(expected.decimalValue().subtract(actual.decimalValue()).abs().compareTo(new BigDecimal("0.000001"))<0,expected+" != "+actual);}

    @Test void unknownAvailabilityNeverMeansZeroDemand()throws Exception {
        ObjectNode f=fixture("01_baseline");f.withObject("dataset").putArray("availability");
        JsonNode row=calculate(f).path("items").get(0);assertEquals("NEEDS_INPUT",row.path("status").asText());assertTrue(row.path("recommended_purchase_qty").isNull());
    }
    @Test void overlappingAvailabilityIsRejected()throws Exception {
        ObjectNode f=fixture("01_baseline");ArrayNode a=f.withObject("dataset").withArray("availability");a.add(a.get(0).deepCopy());assertThrows(ApiException.class,()->calculate(f));
    }
    @Test void assumedAvailabilityIsDisclosed()throws Exception {
        ObjectNode f=fixture("01_baseline");f.withObject("dataset").putArray("availability");
        f.withObject("calculation").withArray("assumptions").addObject().put("code","ASSUME_AVAILABLE").put("scope_id","P1").put("value",true).put("reason","Synthetic availability assumption").put("accepted_by","test");
        JsonNode row=calculate(f).path("items").get(0);assertEquals("ASSUMED",row.path("status").asText());assertEquals(150,row.path("recommended_purchase_qty").asInt());assertEquals("assumed_available",row.path("history").get(0).path("availability_state").asText());
    }
    @Test void returnsNeedExplicitPolicy()throws Exception {
        ObjectNode f=fixture("01_baseline");((ObjectNode)f.path("dataset").path("sales").get(0)).put("operation_type","return");assertEquals("NEEDS_INPUT",calculate(f).path("items").get(0).path("status").asText());
    }
    @Test void estimateSeasonNeedsTwoCompleteCycles()throws Exception {
        ObjectNode f=fixture("01_baseline");f.withObject("calculation").withObject("forecast").put("seasonality_mode","estimate");
        assertEquals("NEEDS_INPUT",calculate(f).path("items").get(0).path("status").asText());
        f.withObject("calculation").put("history_start","2024-07-01");ObjectNode d=f.withObject("dataset");
        ((ObjectNode)d.path("sales_coverage").get(0)).put("start_date","2024-07-01");((ObjectNode)d.path("availability").get(0)).put("start_date","2024-07-01");
        ObjectNode template=(ObjectNode)d.path("sales").get(0);ArrayNode sales=d.putArray("sales");
        for(var day=java.time.LocalDate.of(2024,7,1);day.isBefore(java.time.LocalDate.of(2026,7,1));day=day.plusDays(1))sales.add(template.deepCopy().put("sale_id","S"+day).put("document_id","D"+day).put("date",day.toString()));
        d.putArray("seasonality_profiles");JsonNode row=calculate(f).path("items").get(0);assertEquals("READY",row.path("status").asText());assertEquals(150,row.path("recommended_purchase_qty").asInt());
    }
    @Test void coverageConfirmationRequiresAnExistingDatedSource()throws Exception {
        ObjectNode f=fixture("01_baseline");ObjectNode d=f.withObject("dataset");((ObjectNode)d.path("sales_coverage").get(0)).put("complete",false);((ObjectNode)d.path("inbound_coverage").get(0)).put("complete",false);
        assertEquals("NEEDS_INPUT",calculate(f).path("items").get(0).path("status").asText());
        for(String code:List.of("CONFIRM_SALES_COVERAGE","CONFIRM_INBOUND_SCOPE"))f.withObject("calculation").withArray("assumptions").addObject().put("code",code).put("scope_id","P1").put("value",true).put("reason","Confirmed source scope").put("accepted_by","test");
        assertEquals("ASSUMED",calculate(f).path("items").get(0).path("status").asText());
        d.putArray("inbound_coverage");assertEquals("NEEDS_INPUT",calculate(f).path("items").get(0).path("status").asText());
    }
    @Test void missingCustomerIdsRetainRecurringSkuPatternWithWarning()throws Exception {
        ObjectNode f=fixture("16_repeated_large_orders");f.path("dataset").path("sales").forEach(s->((ObjectNode)s).putNull("customer_id"));
        JsonNode row=calculate(f).path("items").get(0);assertEquals(0,row.path("factors").path("excluded_sales_qty").asInt());
        assertTrue(StreamSupport.stream(row.path("warnings").spliterator(),false).anyMatch(w->w.path("code").asText().equals("CUSTOMER_ID_UNAVAILABLE")));
    }
}
