package kz.uitech;

import static kz.uitech.Model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CalculationEngine {
    private static final MathContext MC=MathContext.DECIMAL128;
    private static final BigDecimal ZERO=BigDecimal.ZERO;
    private final ObjectMapper mapper;
    public CalculationEngine(ObjectMapper mapper) { this.mapper=mapper; }
    private ObjectNode obj() {return mapper.createObjectNode();}
    private static BigDecimal divide(BigDecimal a,long b) {return a.divide(BigDecimal.valueOf(b),MC);}
    private static BigDecimal num(double v) {return BigDecimal.valueOf(v);}
    private static String key(String p,String w) {return p+"\u0000"+w;}
    private static <T> Map<String,List<T>> index(List<T> rows,Function<T,String> f) {return rows.stream().collect(Collectors.groupingBy(f));}
    private boolean assumed(CalculationRequest c,Product p,String code) {
        return c.assumptions().stream().anyMatch(a->a.code().equals(code) && (a.scopeId().equals(p.productId())||a.scopeId().equals(p.supplierId())||a.scopeId().equals("*")));
    }
    public ObjectNode calculate(Dataset d,CalculationRequest c) {
        Validation.calculation(c);
        Set<String> knownSuppliers=d.suppliers().stream().map(Supplier::supplierId).collect(Collectors.toSet());
        if(!knownSuppliers.containsAll(c.supplierIds()))throw ApiException.invalid("Неизвестный фильтр поставщика");
        Set<String> knownCategories=d.products().stream().map(Product::categoryId).filter(Objects::nonNull).collect(Collectors.toSet());
        if(!knownCategories.containsAll(c.categoryIds()))throw ApiException.invalid("Неизвестный фильтр категории");
        Map<String,List<Sale>> sales=index(d.sales(),s->key(s.productId(),s.warehouseId()));
        Map<String,List<Inventory>> stock=index(d.inventory(),s->key(s.productId(),s.warehouseId()));
        Map<String,List<Inbound>> transit=index(d.inbound(),s->key(s.productId(),s.warehouseId()));
        Map<String,List<Availability>> availability=index(d.availability(),s->key(s.productId(),s.warehouseId()));
        Map<String,List<Coverage>> coverage=index(d.salesCoverage(),s->key(s.productId(),s.warehouseId()));
        Map<String,List<InboundCoverage>> inboundCoverage=index(d.inboundCoverage(),s->key(s.productId(),s.warehouseId()));
        Map<String,List<Issue>> issues=index(d.issues(),i->i.productId()==null?"*":i.productId());
        ObjectNode result=obj();result.put("dataset_id",c.datasetId());result.put("revision",1);result.put("status","DRAFT");
        result.put("data_kind",d.dataKind());result.put("as_of",c.asOf().toString());result.put("warehouse_id",c.warehouseId());
        ObjectNode methodology=result.putObject("methodology");methodology.put("version","replenishment_v1");
        methodology.set("parameters",mapper.valueToTree(c));
        methodology.putObject("outlier_parameters").put("modified_z_threshold",3.5).put("minimum_ratio",3).put("minimum_orders",8).put("recurrence_weeks",3);
        ArrayNode items=result.putArray("items");Map<String,ArrayNode> groups=new LinkedHashMap<>();int blocked=0;
        for(Product p:d.products()) {
            if(!c.supplierIds().isEmpty()&&!c.supplierIds().contains(p.supplierId()))continue;
            if(!c.categoryIds().isEmpty()&&!c.categoryIds().contains(p.categoryId()))continue;
            String k=key(p.productId(),c.warehouseId());List<Issue> relevant=new ArrayList<>(issues.getOrDefault("*",List.of()));relevant.addAll(issues.getOrDefault(p.productId(),List.of()));
            ObjectNode row=item(d,c,p,sales.getOrDefault(k,List.of()),stock.getOrDefault(k,List.of()),transit.getOrDefault(k,List.of()),
                    availability.getOrDefault(k,List.of()),coverage.getOrDefault(k,List.of()),inboundCoverage.getOrDefault(k,List.of()),relevant);
            items.add(row);if(row.path("status").asText().equals("NEEDS_INPUT"))blocked++;
            groups.computeIfAbsent(p.supplierId(),s->mapper.createArrayNode()).add(row.path("item_id").asText());
        }
        ArrayNode sg=result.putArray("supplier_groups");groups.forEach((id,ids)->{ObjectNode g=obj();g.put("supplier_id",id);g.set("item_ids",ids);sg.add(g);});
        result.putObject("summary").put("supplier_count",groups.size()).put("item_count",items.size()).put("needs_input_count",blocked);
        result.putNull("approval");return result;
    }
    private ObjectNode item(Dataset d,CalculationRequest c,Product p,List<Sale> allSales,List<Inventory> stocks,
                            List<Inbound> transit,List<Availability> states,List<Coverage> coverage,List<InboundCoverage> ic,List<Issue> sourceIssues) {
        ObjectNode row=obj();row.put("item_id","item_"+p.productId());row.put("product_id",p.productId());row.put("supplier_id",p.supplierId());
        row.put("sku_1c",p.sku1c());row.put("supplier_article",p.supplierArticle());row.put("name",p.name());row.put("category_id",p.categoryId());
        row.put("stock_unit",p.stockUnit());row.put("purchase_unit",p.purchaseUnit());
        row.set("source_refs",mapper.valueToTree(p.sourceRefs()));row.putNull("override");ArrayNode warnings=row.putArray("warnings");
        List<String> blockers=new ArrayList<>();List<Assumption> used=c.assumptions().stream().filter(a->a.scopeId().equals("*")||a.scopeId().equals(p.productId())||a.scopeId().equals(p.supplierId())).toList();
        row.set("assumptions",mapper.valueToTree(used));
        for(Issue issue:sourceIssues) {
            boolean acknowledged=assumed(c,p,issue.code());
            warnings.add(obj().put("code",issue.code()).put("message",issue.message()).put("severity",acknowledged?"warning":issue.severity()));
            if("error".equals(issue.severity())&&!acknowledged)blockers.add(issue.code());
        }
        SupplierPolicy sp=c.supplierPolicies().stream().filter(x->x.supplierId().equals(p.supplierId())).findFirst().orElse(null);
        CategoryPolicy cp=c.categoryPolicies().stream().filter(x->x.categoryId().equals(p.categoryId())).findFirst().orElse(null);
        if(sp==null)blockers.add("SUPPLIER_POLICY_REQUIRED");if(cp==null)blockers.add("CATEGORY_POLICY_REQUIRED");
        Inventory inv=stocks.stream().filter(s->s.asOf().equals(c.asOf())).findFirst().orElse(null);
        if(inv==null||inv.freeStockQty()==null)blockers.add("CURRENT_STOCK_REQUIRED");
        else if(inv.freeStockQty().signum()<0)blockers.add("NEGATIVE_STOCK");
        if(!ic.stream().anyMatch(x->x.asOf().equals(c.asOf())&&(x.complete()||assumed(c,p,"CONFIRM_INBOUND_SCOPE"))))blockers.add("INBOUND_COVERAGE_REQUIRED");
        if(transit.stream().anyMatch(x->x.expectedDate()==null||!"confirmed".equals(x.status())||x.expectedDate().isBefore(c.asOf())))blockers.add("INBOUND_DATE_OR_STATUS_REQUIRED");
        if(p.purchaseUnitFactor()==null||p.moqPurchaseQty()==null||p.packMultiplePurchaseQty()==null)blockers.add("PURCHASE_UNITS_REQUIRED");
        List<Sale> sales=allSales.stream().filter(s->!s.date().isBefore(c.historyStart())&&s.date().isBefore(c.asOf())).toList();
        if(sales.stream().anyMatch(s->"return".equals(s.operationType())))blockers.add("RETURN_POLICY_REQUIRED");
        if(sales.stream().anyMatch(s->s.customerId()==null)) warnings.add(obj().put("code","CUSTOMER_ID_UNAVAILABLE").put("severity","warning").put("message","Клиентские всплески не проверены: нет customer_id; проверяются накладные."));
        Map<LocalDate,String> dayStates=new TreeMap<>();
        for(LocalDate day=c.historyStart();day.isBefore(c.asOf());day=day.plusDays(1)) {
            final LocalDate dt=day;
            if(coverage.stream().noneMatch(x->(x.complete()||assumed(c,p,"CONFIRM_SALES_COVERAGE"))&&!dt.isBefore(x.startDate())&&dt.isBefore(x.endDateExclusive()))) {blockers.add("SALES_COVERAGE_REQUIRED");break;}
            String state=states.stream().filter(a->!dt.isBefore(a.startDate())&&dt.isBefore(a.endDateExclusive())).map(Availability::state).findFirst().orElse("unknown");
            if(state.equals("unknown") && assumed(c,p,"ASSUME_AVAILABLE"))state="assumed_available";
            if(state.equals("unknown")) {blockers.add("AVAILABILITY_REQUIRED");break;}
            dayStates.put(day,state);
        }
        List<BigDecimal> season=selectSeason(d,p);
        if("provided".equals(c.forecast().seasonalityMode()) && season==null)blockers.add("SEASONAL_PROFILE_REQUIRED");
        if(!blockers.isEmpty())return blocked(row,blockers);
        if(sales.stream().map(s->s.date()+"|"+s.documentId()).distinct().count()<8)
            warnings.add(obj().put("code","INSUFFICIENT_OUTLIER_HISTORY").put("severity","warning").put("message","Меньше 8 заказов: автоматическое исключение всплесков ненадёжно; проверьте ряд вручную."));
        if(season==null)season=Collections.nCopies(12,BigDecimal.ONE);
        if("estimate".equals(c.forecast().seasonalityMode())) {
            // A first pass removes isolated events before fitting the seasonal profile.
            Set<String> excluded=outliers(sales,season).excluded();
            season=estimateSeason(sales,excluded,dayStates,c);
            if(season==null)return blocked(row,List.of("INSUFFICIENT_SEASONAL_HISTORY"));
        }
        Detection detection=outliers(sales,season);
        row.set("anomalies",detection.anomalies());
        Map<LocalDate,BigDecimal> actual=new HashMap<>(),regular=new HashMap<>();BigDecimal excludedQty=ZERO;
        for(Sale s:sales) {
            actual.merge(s.date(),s.quantity(),BigDecimal::add);
            if(detection.excluded().contains(s.saleId()))excludedQty=excludedQty.add(s.quantity());else regular.merge(s.date(),s.quantity(),BigDecimal::add);
        }
        BigDecimal deseasonalSum=ZERO;int availableDays=0;
        Map<YearMonth,BigDecimal> monthSums=new TreeMap<>();Map<YearMonth,Integer> monthDays=new TreeMap<>();
        for(var entry:dayStates.entrySet())if(!entry.getValue().equals("stockout")) {
            LocalDate day=entry.getKey();BigDecimal value=regular.getOrDefault(day,ZERO).divide(season.get(day.getMonthValue()-1),MC);
            deseasonalSum=deseasonalSum.add(value);availableDays++;
            monthSums.merge(YearMonth.from(day),value,BigDecimal::add);monthDays.merge(YearMonth.from(day),1,Integer::sum);
        }
        if(availableDays==0)return blocked(row,List.of("NO_AVAILABLE_HISTORY"));
        BigDecimal base=divide(deseasonalSum,availableDays);
        List<Double> trendX=new ArrayList<>(),trendY=new ArrayList<>();YearMonth anchor=YearMonth.from(c.historyStart());
        for(var e:monthSums.entrySet()) {
            YearMonth m=e.getKey();
            if(!m.atDay(1).isBefore(c.historyStart())&&!m.plusMonths(1).atDay(1).isAfter(c.asOf())&&monthDays.get(m)>=Math.max(7,m.lengthOfMonth()/2)) {
                trendX.add((double)ChronoUnit.MONTHS.between(anchor,m));trendY.add(divide(e.getValue(),monthDays.get(m)).doubleValue());
            }
        }
        double slope=0,intercept=base.doubleValue();
        if("historical".equals(c.forecast().growthMode())) {
            if(trendX.size()<3)return blocked(row,List.of("INSUFFICIENT_TREND_HISTORY"));
            double mx=trendX.stream().mapToDouble(x->x).average().orElseThrow(),my=trendY.stream().mapToDouble(x->x).average().orElseThrow();
            double cov=0,var=0;for(int i=0;i<trendX.size();i++){cov+=(trendX.get(i)-mx)*(trendY.get(i)-my);var+=Math.pow(trendX.get(i)-mx,2);}
            slope=var==0?0:cov/var;intercept=my-slope*mx;
        }
        int horizon=sp.leadTimeDays()+sp.reviewPeriodDays();LocalDate end=c.asOf().plusDays(horizon);
        BigDecimal manual="manual".equals(c.forecast().growthMode())?BigDecimal.ONE.add(c.forecast().manualGrowthPct().divide(BigDecimal.valueOf(100),MC)):BigDecimal.ONE;
        BigDecimal demand=ZERO,safety=ZERO;List<BigDecimal> forecastQty=new ArrayList<>();
        for(LocalDate day=c.asOf();day.isBefore(end.plusDays(cp.safetyDays()));day=day.plusDays(1)) {
            BigDecimal level="historical".equals(c.forecast().growthMode())?num(Math.max(0,intercept+slope*ChronoUnit.MONTHS.between(anchor,YearMonth.from(day)))):base;
            BigDecimal value=level.multiply(season.get(day.getMonthValue()-1),MC).multiply(manual,MC);
            if(day.isBefore(end)){demand=demand.add(value);forecastQty.add(value);}else safety=safety.add(value);
        }
        BigDecimal lost=ZERO;ArrayNode history=row.putArray("history");
        for(var e:dayStates.entrySet()) {
            BigDecimal estimate=e.getValue().equals("stockout")?base.multiply(season.get(e.getKey().getMonthValue()-1),MC):ZERO;lost=lost.add(estimate);
            ObjectNode h=obj();h.put("date",e.getKey().toString());h.put("actual_sales_qty",actual.getOrDefault(e.getKey(),ZERO));h.put("regular_sales_qty",regular.getOrDefault(e.getKey(),ZERO));h.put("estimated_lost_qty",estimate);h.put("availability_state",e.getValue());history.add(h);
        }
        BigDecimal eligible=transit.stream().filter(t->t.expectedDate().isBefore(end)).map(Inbound::quantity).reduce(ZERO,BigDecimal::add);
        BigDecimal raw=demand.add(safety).subtract(inv.freeStockQty()).subtract(eligible).max(ZERO);
        BigDecimal purchase=raw.signum()==0?ZERO:raw.divide(p.purchaseUnitFactor(),MC).max(p.moqPurchaseQty())
                .divide(p.packMultiplePurchaseQty(),0,RoundingMode.CEILING).multiply(p.packMultiplePurchaseQty());
        if(!cp.purchasingAllowed())purchase=ZERO;
        row.put("recommended_purchase_qty",purchase);row.put("recommended_stock_qty",purchase.multiply(p.purchaseUnitFactor()));row.put("final_purchase_qty",purchase);
        row.put("status",!cp.purchasingAllowed()?"EXCLUDED_BY_POLICY":used.isEmpty()?"READY":"ASSUMED");
        BigDecimal balance=inv.freeStockQty();LocalDate firstShortage=null;ArrayNode forecast=row.putArray("forecast");
        for(int i=0;i<horizon;i++) {
            LocalDate day=c.asOf().plusDays(i);BigDecimal arrivals=transit.stream().filter(t->t.expectedDate().equals(day)).map(Inbound::quantity).reduce(ZERO,BigDecimal::add);
            balance=balance.add(arrivals);BigDecimal unmet=forecastQty.get(i).subtract(balance).max(ZERO);balance=balance.subtract(forecastQty.get(i)).max(ZERO);
            if(unmet.signum()>0&&firstShortage==null)firstShortage=day;
            forecast.add(obj().put("date",day.toString()).put("demand_qty",forecastQty.get(i)).put("projected_stock_qty",balance).put("unmet_demand_qty",unmet));
        }
        LocalDate nextDelivery=transit.stream().map(Inbound::expectedDate).min(Comparator.naturalOrder()).orElse(c.asOf().plusDays(sp.leadTimeDays()));
        if(c.asOf().plusDays(sp.leadTimeDays()).isBefore(nextDelivery))nextDelivery=c.asOf().plusDays(sp.leadTimeDays());
        row.put("first_stockout_date",firstShortage==null?null:firstShortage.toString());
        row.put("urgency",firstShortage==null?"NORMAL":firstShortage.isBefore(nextDelivery)?"BEFORE_NEXT_DELIVERY":"WITHIN_HORIZON");
        ObjectNode f=row.putObject("factors");f.put("base_daily_rate",base);f.put("horizon_days",horizon);f.put("forecast_horizon_qty",demand);f.put("safety_stock_qty",safety);
        f.put("free_stock_qty",inv.freeStockQty());f.put("eligible_inbound_qty",eligible);f.put("raw_order_stock_qty",raw);f.put("lost_demand_estimate_qty",lost);f.put("excluded_sales_qty",excludedQty);
        f.put("available_days",availableDays);f.put("trend_slope_per_month",slope);f.set("seasonality_indices",mapper.valueToTree(season));
        row.put("explanation",String.format(Locale.ROOT,"Спрос %s + страховой запас %s − свободный остаток %s − поставки %s = %s %s. Заказ с учётом единиц, MOQ и кратности: %s %s.%s",
                fmt(demand),fmt(safety),fmt(inv.freeStockQty()),fmt(eligible),fmt(raw),p.stockUnit(),fmt(purchase),p.purchaseUnit(),cp.purchasingAllowed()?"":" Закупка запрещена политикой категории."));
        return row;
    }
    private static String fmt(BigDecimal v) {return v.setScale(3,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();}
    private ObjectNode blocked(ObjectNode row,List<String> codes) {
        row.put("status","NEEDS_INPUT");row.put("urgency","UNKNOWN");row.putNull("first_stockout_date");row.putNull("recommended_purchase_qty");row.putNull("recommended_stock_qty");row.putNull("final_purchase_qty");
        row.putObject("factors");row.putArray("history");row.putArray("forecast");row.putArray("anomalies");
        for(String code:new LinkedHashSet<>(codes))((ArrayNode)row.get("warnings")).add(obj().put("code",code).put("severity","error").put("message","Требуется уточнение: "+code));
        row.put("explanation","Расчёт заблокирован: "+String.join(", ",new LinkedHashSet<>(codes)));return row;
    }
    private List<BigDecimal> selectSeason(Dataset d,Product p) {
        for(String scope:List.of("product","category","supplier")) {
            String id=switch(scope){case "product"->p.productId();case "category"->p.categoryId();default->p.supplierId();};
            Optional<Seasonality> profile=d.seasonalityProfiles().stream().filter(s->s.scopeType().equals(scope)&&s.scopeId().equals(id)).findFirst();
            if(profile.isPresent())return profile.get().indices();
        }
        return null;
    }
    private List<BigDecimal> estimateSeason(List<Sale> sales,Set<String> excluded,Map<LocalDate,String> states,CalculationRequest c) {
        Map<YearMonth,BigDecimal> sums=new HashMap<>();Map<YearMonth,Integer> days=new HashMap<>();
        for(Sale s:sales)if(!excluded.contains(s.saleId()))sums.merge(YearMonth.from(s.date()),s.quantity(),BigDecimal::add);
        for(var e:states.entrySet())if(!e.getValue().equals("stockout"))days.merge(YearMonth.from(e.getKey()),1,Integer::sum);
        List<BigDecimal> rates=new ArrayList<>();
        for(int month=1;month<=12;month++) {
            List<BigDecimal> values=new ArrayList<>();
            for(var e:days.entrySet())if(e.getKey().getMonthValue()==month && e.getValue()>=14 && !e.getKey().atDay(1).isBefore(c.historyStart()) && !e.getKey().plusMonths(1).atDay(1).isAfter(c.asOf()))values.add(divide(sums.getOrDefault(e.getKey(),ZERO),e.getValue()));
            if(values.size()<2)return null;rates.add(divide(values.stream().reduce(ZERO,BigDecimal::add),values.size()));
        }
        BigDecimal mean=divide(rates.stream().reduce(ZERO,BigDecimal::add),12);if(mean.signum()==0||rates.stream().anyMatch(v->v.signum()==0))return null;
        return rates.stream().map(v->v.divide(mean,MC)).toList();
    }
    private record Order(LocalDate date,String customer,List<Sale> sales,double normalizedQty) {}
    private record Detection(Set<String> excluded,ArrayNode anomalies) {}
    private Detection outliers(List<Sale> sales,List<BigDecimal> season) {
        Set<String> excluded=new HashSet<>();ArrayNode anomalies=mapper.createArrayNode();
        Map<String,List<Sale>> docGroups=index(sales,s->s.date()+"|"+s.documentId());
        List<Order> orders=orders(docGroups,season);detect(orders,excluded,anomalies,"LARGE_DOCUMENT");
        List<Sale> identified=sales.stream().filter(s->s.customerId()!=null).toList();
        detect(orders(index(identified,s->s.date()+"|"+s.customerId()),season),excluded,anomalies,"LARGE_CUSTOMER_DAY");
        return new Detection(excluded,anomalies);
    }
    private List<Order> orders(Map<String,List<Sale>> groups,List<BigDecimal> season) {
        return groups.values().stream().map(rows->{Sale first=rows.get(0);double qty=rows.stream().map(Sale::quantity).reduce(ZERO,BigDecimal::add).divide(season.get(first.date().getMonthValue()-1),MC).doubleValue();return new Order(first.date(),first.customerId(),rows,qty);}).sorted(Comparator.comparing(Order::date).thenComparing(o->o.sales().get(0).documentId())).toList();
    }
    private static double median(List<Double> values) {List<Double> a=values.stream().sorted().toList();int n=a.size();return n%2==1?a.get(n/2):(a.get(n/2-1)+a.get(n/2))/2;}
    private void detect(List<Order> orders,Set<String> excluded,ArrayNode anomalies,String reason) {
        if(orders.size()<8)return;
        double med=median(orders.stream().map(Order::normalizedQty).toList());
        double mad=median(orders.stream().map(o->Math.abs(o.normalizedQty()-med)).toList());
        double threshold=Math.max(3*med,med+3.5*mad/.67448975);
        for(Order order:orders) {
            if(order.normalizedQty()<=threshold)continue;
            // Without customer IDs, retain recurring SKU-level patterns conservatively; no client identity is inferred.
            long recurringWeeks=orders.stream().filter(o->(order.customer()==null||Objects.equals(order.customer(),o.customer()))&&o.normalizedQty()>=order.normalizedQty()*.5&&o.normalizedQty()<=order.normalizedQty()*2)
                    .map(o->o.date().with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))).distinct().count();
            if(recurringWeeks>=3)continue;
            List<Sale> newlyExcluded=order.sales().stream().filter(s->excluded.add(s.saleId())).toList();if(newlyExcluded.isEmpty())continue;
            BigDecimal qty=newlyExcluded.stream().map(Sale::quantity).reduce(ZERO,BigDecimal::add);
            ObjectNode anomaly=obj();anomaly.set("document_ids",mapper.valueToTree(newlyExcluded.stream().map(Sale::documentId).distinct().toList()));
            anomaly.put("customer_id",order.customer());anomaly.put("date",order.date().toString());anomaly.put("original_qty",order.sales().stream().map(Sale::quantity).reduce(ZERO,BigDecimal::add));
            anomaly.put("excluded_qty",qty);anomaly.put("reason",reason);anomaly.put("decision","excluded_by_rule");anomalies.add(anomaly);
        }
    }
}
