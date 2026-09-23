package kz.uitech;

import static kz.uitech.Model.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Function;

public final class Validation {
    private Validation() {}
    static void require(boolean ok, String message) { if (!ok) throw ApiException.invalid(message); }
    static void text(String value, String name) { require(value != null && !value.isBlank(), name + " обязателен"); }
    static void number(BigDecimal n, String name, boolean zeroAllowed) {
        require(n != null && (zeroAllowed ? n.signum() >= 0 : n.signum() > 0) && n.abs().compareTo(new BigDecimal("9007199254740991")) <= 0,
                name + " должен быть допустимым " + (zeroAllowed ? "неотрицательным" : "положительным") + " числом");
    }
    static <T> Set<String> unique(List<T> rows, Function<T,String> key, String label) {
        Set<String> ids = new HashSet<>();
        for (T row : rows) { require(row!=null, label+": null-строка"); String id=key.apply(row); text(id,label); require(ids.add(id),label+": повтор "+id); }
        return ids;
    }
    static void interval(LocalDate a, LocalDate b) { require(a!=null && b!=null && a.isBefore(b), "Некорректный интервал дат"); }
    public static void dataset(Dataset d) {
        require(d!=null, "Набор обязателен"); text(d.name(),"name");
        require("1.0".equals(d.schemaVersion()), "Поддерживается schema_version 1.0");
        require(Set.of("synthetic","real").contains(Objects.toString(d.dataKind(),"")), "Некорректный data_kind");
        try { ZoneId.of(d.timezone()); } catch (Exception e) { throw ApiException.invalid("Неизвестная timezone"); }
        require(!d.products().isEmpty(),"Нужен хотя бы один товар");
        Set<String> suppliers=unique(d.suppliers(),Supplier::supplierId,"supplier_id");
        Set<String> products=unique(d.products(),Product::productId,"product_id");
        unique(d.products(),p->p.supplierId()+"\u0000"+p.sku1c(),"supplier + sku_1c");
        for (Product p:d.products()) {
            require(suppliers.contains(p.supplierId()),"Неизвестный поставщик "+p.productId());
            text(p.sku1c(),"sku_1c"); text(p.name(),"name");text(p.stockUnit(),"stock_unit");text(p.purchaseUnit(),"purchase_unit");
            if(p.purchaseUnitFactor()!=null)number(p.purchaseUnitFactor(),"purchase_unit_factor",false);
            if(p.moqPurchaseQty()!=null)number(p.moqPurchaseQty(),"moq_purchase_qty",true);
            if(p.packMultiplePurchaseQty()!=null)number(p.packMultiplePurchaseQty(),"pack_multiple_purchase_qty",false);
        }
        unique(d.sales(),Sale::saleId,"sale_id");
        for(Sale s:d.sales()) {
            require(products.contains(s.productId()),"Неизвестный товар продажи"); text(s.warehouseId(),"warehouse_id");
            text(s.documentId(),"document_id"); require(s.date()!=null,"Дата продажи обязательна");number(s.quantity(),"quantity",false);
            require(Set.of("sale","return").contains(Objects.toString(s.operationType(),"")),"Неподдерживаемый operation_type");
        }
        unique(d.inventory(),i->i.productId()+"|"+i.warehouseId()+"|"+i.asOf(),"inventory snapshot");
        for(Inventory i:d.inventory()) { require(products.contains(i.productId()),"Неизвестный товар остатка");text(i.warehouseId(),"warehouse_id");require(i.asOf()!=null,"Дата остатка обязательна"); }
        unique(d.inbound(),Inbound::shipmentId,"shipment_id");
        for(Inbound i:d.inbound()) { require(products.contains(i.productId()),"Неизвестный товар поставки");text(i.warehouseId(),"warehouse_id");number(i.quantity(),"inbound quantity",false);require(Set.of("confirmed","unconfirmed").contains(Objects.toString(i.status(),"")),"Статус поставки не поддерживается"); }
        for(Coverage c:d.salesCoverage()) { require(products.contains(c.productId()),"Неизвестный товар coverage");text(c.warehouseId(),"warehouse_id");interval(c.startDate(),c.endDateExclusive()); }
        unique(d.inboundCoverage(),c->c.productId()+"|"+c.warehouseId()+"|"+c.asOf(),"inbound coverage");
        for(InboundCoverage c:d.inboundCoverage()) { require(products.contains(c.productId()),"Неизвестный товар inbound coverage");text(c.warehouseId(),"warehouse_id");require(c.asOf()!=null,"Дата inbound coverage обязательна"); }
        Map<String,List<Availability>> periods=new HashMap<>();
        for(Availability a:d.availability()) {
            require(products.contains(a.productId()),"Неизвестный товар availability");interval(a.startDate(),a.endDateExclusive());text(a.warehouseId(),"warehouse_id");
            require(Set.of("available","stockout","unknown").contains(Objects.toString(a.state(),"")),"Неизвестное состояние availability");
            require(Set.of("observed","manual","synthetic").contains(Objects.toString(a.provenance(),"")),"Неизвестное происхождение availability");
            periods.computeIfAbsent(a.productId()+"|"+a.warehouseId(),k->new ArrayList<>()).add(a);
        }
        for(List<Availability> values:periods.values()) {
            values.sort(Comparator.comparing(Availability::startDate));
            for(int i=1;i<values.size();i++)require(!values.get(i).startDate().isBefore(values.get(i-1).endDateExclusive()),"Пересекающиеся периоды наличия");
        }
        for(Sale s:d.sales()) for(Availability a:periods.getOrDefault(s.productId()+"|"+s.warehouseId(),List.of()))
            require(!("sale".equals(s.operationType()) && "stockout".equals(a.state()) && !s.date().isBefore(a.startDate()) && s.date().isBefore(a.endDateExclusive())),"Продажа в подтверждённый день stockout");
        unique(d.seasonalityProfiles(),Seasonality::profileId,"profile_id");
        unique(d.seasonalityProfiles(),s->s.scopeType()+"|"+s.scopeId(),"seasonality scope");
        for(Seasonality s:d.seasonalityProfiles()) {
            require(Set.of("product","category","supplier").contains(Objects.toString(s.scopeType(),"")),"Неподдерживаемый scope_type");text(s.scopeId(),"scope_id");
            if("product".equals(s.scopeType())) require(products.contains(s.scopeId()),"Неизвестный product scope");
            if("supplier".equals(s.scopeType())) require(suppliers.contains(s.scopeId()),"Неизвестный supplier scope");
            require("daily_rate".equals(s.basis()) && s.indices().size()==12,"Нужны 12 сезонных индексов дневной скорости");
            BigDecimal sum=BigDecimal.ZERO;for(BigDecimal v:s.indices()) {number(v,"seasonality index",false);sum=sum.add(v);}
            require(sum.subtract(BigDecimal.valueOf(12)).abs().compareTo(new BigDecimal("0.00001"))<0,"Средний сезонный индекс должен быть 1");
        }
        for(List<MonthlyValue> values:List.of(d.monthlySales(),d.monthlyStock()))for(MonthlyValue m:values) {require(products.contains(m.productId()),"Неизвестный товар monthly value");require(m.month()!=null,"Месяц обязателен");}
    }
    public static void calculation(CalculationRequest c) {
        require(c!=null,"Настройки обязательны");text(c.datasetId(),"dataset_id");text(c.warehouseId(),"warehouse_id");interval(c.historyStart(),c.asOf());
        require(c.historyStart().plusYears(10).isAfter(c.asOf()),"История ограничена 10 годами");
        unique(c.supplierPolicies(),SupplierPolicy::supplierId,"supplier policy");
        for(SupplierPolicy p:c.supplierPolicies()) require(p.leadTimeDays()!=null && p.leadTimeDays()>=0 && p.reviewPeriodDays()!=null && p.reviewPeriodDays()>0 && (long)p.leadTimeDays()+p.reviewPeriodDays()<=366,"Горизонт должен быть 1–366 дней");
        unique(c.categoryPolicies(),CategoryPolicy::categoryId,"category policy");
        for(CategoryPolicy p:c.categoryPolicies())require(p.safetyDays()!=null && p.safetyDays()>=0 && p.safetyDays()<=366 && p.purchasingAllowed()!=null,"Некорректная политика категории");
        ForecastConfig f=c.forecast();require(f!=null,"forecast обязателен");
        require(Set.of("provided","estimate").contains(Objects.toString(f.seasonalityMode(),"")),"Некорректный seasonality_mode");
        require(Set.of("manual","historical").contains(Objects.toString(f.growthMode(),"")),"Некорректный growth_mode");
        require("robust_orders_v1".equals(f.outlierPolicy()) && "confirmed_only".equals(f.stockoutMode()),"Неподдерживаемая методология");
        if("manual".equals(f.growthMode()))require(f.manualGrowthPct()!=null && f.manualGrowthPct().compareTo(BigDecimal.valueOf(-100))>=0 && f.manualGrowthPct().compareTo(BigDecimal.valueOf(10000))<=0,"manual_growth_pct: от -100 до 10000");
        else require(f.manualGrowthPct()==null,"Ручной прирост нельзя применять вместе с историческим трендом");
        unique(c.assumptions(),a->a.code()+"|"+a.scopeId(),"assumption");
        for(Assumption a:c.assumptions()) {
            require(Set.of("ASSUME_AVAILABLE","CONFIRM_SNAPSHOT_SCOPE","CONFIRM_INBOUND_SCOPE","CONFIRM_SALES_COVERAGE","EXCLUDE_QUARANTINED_OPERATIONS").contains(Objects.toString(a.code(),"")),"Неподдерживаемое допущение");
            text(a.scopeId(),"scope_id");text(a.reason(),"reason");text(a.acceptedBy(),"accepted_by");
            require(a.value()!=null && a.value().isBoolean() && a.value().booleanValue(),"Допущение должно иметь value=true");
        }
    }
}
