package kz.uitech;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class Model {
    private Model() {}
    public static <T> List<T> list(List<T> v) { return v == null ? List.of() : List.copyOf(v); }
    public record Ref(String sourceId, String sheet, String cellRange) {}
    public record Source(String sourceId, String kind, String role, String fileName, String note) {}
    public record Issue(String code, String severity, String productId, String message, List<Ref> sourceRefs) {
        public Issue { sourceRefs = list(sourceRefs); }
    }
    public record Supplier(String supplierId, String name) {}
    public record Product(String productId, String supplierId, @JsonProperty("sku_1c") String sku1c, String supplierArticle,
                          String name, String stockUnit, String purchaseUnit, BigDecimal purchaseUnitFactor,
                          String categoryId, BigDecimal moqPurchaseQty, BigDecimal packMultiplePurchaseQty,
                          List<Ref> sourceRefs) { public Product { sourceRefs = list(sourceRefs); } }
    public record Coverage(String productId, String warehouseId, LocalDate startDate, LocalDate endDateExclusive, boolean complete) {}
    public record Sale(String saleId, String productId, String warehouseId, LocalDate date, String documentId,
                       String customerId, String operationType, BigDecimal quantity, List<Ref> sourceRefs) {
        public Sale { sourceRefs = list(sourceRefs); }
    }
    public record Inventory(String productId, String warehouseId, LocalDate asOf, BigDecimal freeStockQty, List<Ref> sourceRefs) {
        public Inventory { sourceRefs = list(sourceRefs); }
    }
    public record Inbound(String shipmentId, String productId, String warehouseId, BigDecimal quantity,
                          LocalDate expectedDate, String status, List<Ref> sourceRefs) {
        public Inbound { sourceRefs = list(sourceRefs); }
    }
    public record InboundCoverage(String productId, String warehouseId, LocalDate asOf, boolean complete) {}
    public record Availability(String productId, String warehouseId, LocalDate startDate,
                               LocalDate endDateExclusive, String state, String provenance, List<Ref> sourceRefs) {
        public Availability { sourceRefs = list(sourceRefs); }
    }
    public record Seasonality(String profileId, String scopeType, String scopeId, String basis,
                              List<BigDecimal> indices, List<Ref> sourceRefs) {
        public Seasonality { indices = list(indices); sourceRefs = list(sourceRefs); }
    }
    public record MonthlyValue(String productId, String warehouseId, YearMonth month, BigDecimal quantity,
                               String meaning, List<Ref> sourceRefs) { public MonthlyValue { sourceRefs = list(sourceRefs); } }
    public record Dataset(String schemaVersion, String name, String dataKind, String timezone,
                          List<Supplier> suppliers, List<Product> products, List<Coverage> salesCoverage,
                          List<Sale> sales, List<Inventory> inventory, List<Inbound> inbound,
                          List<InboundCoverage> inboundCoverage, List<Availability> availability,
                          List<Seasonality> seasonalityProfiles, List<Source> sources, List<Issue> issues,
                          List<MonthlyValue> monthlySales, List<MonthlyValue> monthlyStock) {
        public Dataset {
            suppliers=list(suppliers); products=list(products); salesCoverage=list(salesCoverage); sales=list(sales);
            inventory=list(inventory); inbound=list(inbound); inboundCoverage=list(inboundCoverage);
            availability=list(availability); seasonalityProfiles=list(seasonalityProfiles);
            sources=list(sources); issues=list(issues); monthlySales=list(monthlySales); monthlyStock=list(monthlyStock);
        }
    }
    public record SupplierPolicy(String supplierId, Integer leadTimeDays, Integer reviewPeriodDays) {}
    public record CategoryPolicy(String categoryId, Integer safetyDays, Boolean purchasingAllowed) {}
    public record ForecastConfig(String seasonalityMode, String growthMode, BigDecimal manualGrowthPct,
                                 String outlierPolicy, String stockoutMode) {}
    public record Assumption(String code, String scopeId, JsonNode value, String reason, String acceptedBy) {}
    public record CalculationRequest(String datasetId, LocalDate asOf, String warehouseId,
                                     List<String> supplierIds, List<String> categoryIds, LocalDate historyStart,
                                     List<SupplierPolicy> supplierPolicies, List<CategoryPolicy> categoryPolicies,
                                     ForecastConfig forecast, List<Assumption> assumptions) {
        public CalculationRequest {
            supplierIds=list(supplierIds); categoryIds=list(categoryIds); supplierPolicies=list(supplierPolicies);
            categoryPolicies=list(categoryPolicies); assumptions=list(assumptions);
        }
    }
    public record ManifestFile(String partName, String supplierId, String role) {}
    public record ImportManifest(String name, String dataKind, String timezone, List<ManifestFile> files) {}
}
