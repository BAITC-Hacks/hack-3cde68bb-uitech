package kz.uitech;

import static kz.uitech.Model.*;
import static kz.uitech.Validation.*;
import java.math.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Service;

/** Adapter for the six IEK workbooks supplied for HackAlem, September 2026. */
@Service
public class IekImporter {
    private static final List<String> ROLES=List.of("moq","sales_monthly","stock_monthly","sales_transactions","inventory_transit","seasonality");
    private static final DateTimeFormatter DATE_TIME=DateTimeFormatter.ofPattern("dd.MM.uuuu H:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern DEADLINE=Pattern.compile("поступление\\s+до\\s+(\\d{2}\\.\\d{2}\\.\\d{4})",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
    private static final MathContext MC=MathContext.DECIMAL128;
    private final XlsxReader reader=new XlsxReader();

    private static class Item {
        String code,name,article,unit;
        List<Ref> refs=new ArrayList<>();
        String id(){return "IEK:"+code;}
        Product product(){return new Product(id(),"IEK",code,article,name,
                unit==null?"не уточнена":unit,"не уточнена",null,null,null,null,refs);}
    }
    private static class Data {
        Map<String,Item> items=new LinkedHashMap<>();
        List<Sale> sales=new ArrayList<>();
        List<MonthlyValue> monthlySales=new ArrayList<>(),monthlyStock=new ArrayList<>();
        List<Source> sources=new ArrayList<>();List<Issue> issues=new ArrayList<>();
        List<ReportedInbound> reportedInbound=new ArrayList<>();
        List<ReportedPurchaseRule> reportedRules=new ArrayList<>();
        List<Seasonality> seasonality=new ArrayList<>();
        Item item(String code,String name){
            return items.computeIfAbsent(code,k->{Item i=new Item();i.code=k;i.name=clean(name)==null?k:clean(name);return i;});
        }
        void issue(String code,String severity,Item item,String message,List<Ref> refs){issues.add(new Issue(code,severity,item==null?null:item.id(),message,refs));}
        void unit(Item item,String value,List<Ref> refs){
            value=clean(value);if(value==null)return;
            if(item.unit!=null&&!item.unit.equals(value))issue("STOCK_UNIT_CONFLICT","error",item,"Единицы хранения расходятся: "+item.unit+" / "+value,refs);
            else item.unit=value;
        }
    }
    static String clean(String value){return value==null||value.isBlank()?null:value.strip();}
    static BigDecimal number(String value){
        if(clean(value)==null)return null;
        try{return new BigDecimal(value.replace("\u00a0","").replace(" ","").replace(',','.'));}
        catch(NumberFormatException e){return null;}
    }
    static List<Ref> ref(String role,String sheet,String range){return List.of(new Ref("iek_"+role,sheet,range));}
    static String cell(int col,int row){return CellReference.convertNumToColString(col)+row;}
    static void header(XlsxReader.Row row,int column,String expected){
        require(expected.equals(clean(row.cell(column))),"Неожиданный шаблон IEK: "+cell(column,row.number())+", ожидалось «"+expected+"»");
    }
    static LocalDate deadline(String title){
        var matcher=DEADLINE.matcher(Objects.toString(title,"").replace('\u00a0',' '));
        require(matcher.find(),"Не найден срок «поступление до» в заголовке партии IEK");
        try{return LocalDate.parse(matcher.group(1),DATE);}catch(DateTimeParseException e){throw ApiException.invalid("Некорректная дата партии IEK");}
    }
    public Dataset parse(ImportManifest manifest,Map<String,SystemeImporter.FileInput> files){
        require(manifest!=null,"manifest обязателен");text(manifest.name(),"name");
        require("real".equals(manifest.dataKind()),"Импорт XLSX имеет data_kind=real");
        require(manifest.files()!=null&&manifest.files().size()==6,"Для IEK нужны все шесть файлов");
        Map<String,ManifestFile> roles=new LinkedHashMap<>();Set<String> parts=new HashSet<>();
        for(ManifestFile f:manifest.files()){
            require(f!=null&&"IEK".equals(f.supplierId()),"Этот адаптер поддерживает supplier_id=IEK");
            require(f.role()!=null&&ROLES.contains(f.role())&&roles.putIfAbsent(f.role(),f)==null,"Неизвестная или повторная роль файла");
            require(f.partName()!=null&&parts.add(f.partName())&&files.containsKey(f.partName()),"Части файлов отсутствуют или повторяются");
        }
        Data d=new Data();
        for(String role:ROLES){
            var file=files.get(roles.get(role).partName());
            d.sources.add(new Source("iek_"+role,"xlsx",role,file.name(),"IEK: исходные значения, границы месяцев и ссылки на ячейки; неизвестные единицы не преобразуются."));
            switch(role){
                case "moq" -> purchasing(d,file.bytes());
                case "sales_monthly" -> monthly(d,file.bytes(),false);
                case "stock_monthly" -> monthly(d,file.bytes(),true);
                case "sales_transactions" -> sales(d,file.bytes());
                case "inventory_transit" -> transit(d,file.bytes());
                case "seasonality" -> seasonality(d,file.bytes());
                default -> throw new IllegalStateException(role);
            }
        }
        require(!d.items.isEmpty(),"В файлах IEK не найдены товары");
        Map<String,LocalDate> first=new TreeMap<>(),last=new TreeMap<>();
        Map<String,BigDecimal> monthlyDetail=new HashMap<>();
        for(Sale sale:d.sales){
            String key=sale.productId()+"|"+sale.warehouseId();
            first.merge(key,sale.date(),(a,b)->a.isBefore(b)?a:b);last.merge(key,sale.date(),(a,b)->a.isAfter(b)?a:b);
            monthlyDetail.merge(sale.productId()+"|"+YearMonth.from(sale.date()),sale.quantity(),BigDecimal::add);
        }
        List<Coverage> coverage=new ArrayList<>();
        first.forEach((key,start)->{int cut=key.lastIndexOf('|');coverage.add(new Coverage(key.substring(0,cut),key.substring(cut+1),start,last.get(key).plusDays(1),false));});
        Map<String,List<Ref>> mismatches=new LinkedHashMap<>();
        for(MonthlyValue m:d.monthlySales){BigDecimal detailed=monthlyDetail.get(m.productId()+"|"+m.month());
            if(m.quantity()!=null&&detailed!=null&&m.quantity().compareTo(detailed)!=0)mismatches.computeIfAbsent(m.productId(),k->new ArrayList<>()).addAll(m.sourceRefs());}
        mismatches.forEach((pid,refs)->d.issues.add(new Issue("MONTHLY_SALES_MISMATCH","warning",pid,"Месячный отчёт расходится с принятыми накладными в "+refs.size()+" месяцах с данными в обоих источниках. Область отчётов требует сверки.",refs)));
        d.issue("CURRENT_STOCK_UNAVAILABLE","warning",null,"В IEK нет текущего свободного остатка. Начальный остаток сентября не является снимком на дату расчёта.",List.of());
        d.issue("CATEGORY_UNAVAILABLE","warning",null,"Категории IEK не предоставлены. Назначьте категории и правила запаса вручную.",List.of());
        d.issue("PURCHASE_RULES_UNCONFIRMED","warning",null,"«Мин. разр. к отгр.» сохранён в reported_purchase_rules. Уточните, означает ли он минимум, кратность или оба ограничения, а также единицу закупки.",List.of());
        d.issue("REPORTED_INBOUND_UNCONFIRMED","warning",null,"Партии сохранены в reported_inbound. Единицы, склад, полнота и даты получения не подтверждены. Количества не включены в доступный транзит; пустая ячейка не признана нулём.",List.of());
        d.issue("SALES_SCOPE_UNCONFIRMED","warning",null,"Полнота детализации и область месячных отчётов не подтверждены. Ряды не суммируются.",List.of());
        d.issue("CUSTOMER_ID_UNAVAILABLE","warning",null,"В детализации IEK нет обезличенного ID клиента.",List.of());
        d.issue("AVAILABILITY_UNAVAILABLE","warning",null,"Начальные месячные остатки не восстанавливают календарь дневного stockout.",List.of());
        return new Dataset("1.0",manifest.name(),"real",manifest.timezone(),List.of(new Supplier("IEK","IEK")),
                d.items.values().stream().map(Item::product).toList(),coverage,d.sales,List.of(),List.of(),List.of(),List.of(),
                d.seasonality,d.sources,d.issues,d.monthlySales,d.monthlyStock,d.reportedInbound,d.reportedRules);
    }
    private void purchasing(Data d,byte[] bytes){
        Map<String,List<Ref>> seen=new HashMap<>();
        reader.read(bytes,"Лист7",r->{
            if(r.number()==1){header(r,1,"Код 1с");header(r,4,"Мин. разр. к отгр.");return;}
            String code=clean(r.cell(1));if(code==null)return;
            Item item=d.item(code,r.cell(3));item.article=clean(r.cell(2));var refs=ref("moq","Лист7","B"+r.number()+":E"+r.number());item.refs.addAll(refs);
            BigDecimal qty=number(r.cell(4));
            d.reportedRules.add(new ReportedPurchaseRule("iek_moq-"+r.number(),item.id(),"Мин. разр. к отгр.",r.cell(4),qty,null,refs));
            if(qty==null||qty.signum()<=0)d.issue("INVALID_MIN_SHIPMENT","error",item,"Не распознано положительное ограничение отгрузки: "+Objects.toString(r.cell(4),"<пусто>"),refs);
            var previous=seen.putIfAbsent(code,refs);if(previous!=null){var both=new ArrayList<>(previous);both.addAll(refs);d.issue("DUPLICATE_PURCHASE_RULE","error",item,"Повтор кода в ограничениях отгрузки. Значения не объединены и не выбраны автоматически.",both);}
        });
    }
    private void monthly(Data d,byte[] bytes,boolean stock){
        String role=stock?"stock_monthly":"sales_monthly";int codeCol=stock?2:1,firstCol=stock?3:2,start=stock?4:3;
        Set<String> seen=new HashSet<>();
        reader.read(bytes,"Лист_1",r->{
            if(r.number()==1){header(r,codeCol,"Номенклатура.Код");header(r,firstCol,"янв. 2024");header(r,firstCol+32,"сент. 2026");return;}
            if(stock&&r.number()==3){header(r,firstCol,"нач. остаток");return;}
            if(r.number()<start)return;String code=clean(r.cell(codeCol));if(code==null)return;
            Item item=d.item(code,r.cell(0));var refs=ref(role,"Лист_1",cell(0,r.number())+":"+cell(codeCol,r.number()));item.refs.addAll(refs);
            if(stock)d.unit(item,r.cell(1),refs);
            if(!seen.add(code))d.issue("DUPLICATE_MONTHLY_ROW","error",item,"Повтор строки месячного отчёта; записи сохранены отдельно.",refs);
            for(int i=0;i<33;i++){
                String raw=r.cell(firstCol+i);BigDecimal qty=number(raw);var source=ref(role,"Лист_1",cell(firstCol+i,r.number()));
                if(clean(raw)!=null&&qty==null)d.issue("INVALID_MONTHLY_QUANTITY","error",item,"Нечисловое значение в месячном отчёте: "+raw,source);
                (stock?d.monthlyStock:d.monthlySales).add(new MonthlyValue(item.id(),"UNCONFIRMED",YearMonth.of(2024,1).plusMonths(i),qty,stock?"reported_month_start_stock":"reported_monthly_sales",source));
            }
        });
    }
    private void sales(Data d,byte[] bytes){
        reader.read(bytes,"Лист_1",r->{
            if(r.number()==1){header(r,0,"Дата");header(r,3,"Код");header(r,7,"Количество");return;}
            String code=clean(r.cell(3));if(code==null)return;
            Item item=d.item(code,r.cell(4));var refs=ref("sales_transactions","Лист_1","A"+r.number()+":H"+r.number());d.unit(item,r.cell(5),refs);
            BigDecimal qty=number(r.cell(7));LocalDate day;
            try{day=LocalDateTime.parse(r.cell(0),DATE_TIME).toLocalDate();}
            catch(Exception e){d.issue("EXCLUDE_QUARANTINED_OPERATIONS","error",item,"Не распознана дата: "+r.cell(0),refs);return;}
            String document=clean(r.cell(1)),warehouse=clean(r.cell(6));
            if(qty==null||qty.signum()<=0||document==null||warehouse==null||!Objects.toString(r.cell(2),"").startsWith("Расходная накладная")){
                d.issue("EXCLUDE_QUARANTINED_OPERATIONS","error",item,"Операция не включена в продажи. Исходное количество: "+Objects.toString(r.cell(7),"<пусто>")+"; тип: "+Objects.toString(r.cell(2),"<пусто>"),refs);return;
            }
            d.sales.add(new Sale("iek_sales_transactions-"+r.number(),item.id(),warehouse,day,document,null,"sale",qty,refs));
        });
    }
    private void transit(Data d,byte[] bytes){
        LocalDate[] dates=new LocalDate[6];String[] labels=new String[6];Map<String,List<Ref>> seen=new HashMap<>();
        reader.read(bytes,"Лист4",r->{
            if(r.number()==1){header(r,0,"Код 1с");header(r,1,"Артикул ИЭК");for(int i=0;i<6;i++){labels[i]=r.cell(i+3);dates[i]=deadline(labels[i]);}return;}
            String code=clean(r.cell(0));if(code==null)return;Item item=d.item(code,r.cell(2));
            if(item.article==null)item.article=clean(r.cell(1));var refs=ref("inventory_transit","Лист4","A"+r.number()+":I"+r.number());item.refs.addAll(refs);
            var previous=seen.putIfAbsent(code,refs);if(previous!=null){var both=new ArrayList<>(previous);both.addAll(refs);d.issue("DUPLICATE_INBOUND_ROW","error",item,"Повтор кода в таблице партий. Строки сохранены отдельно; без уточнения их нельзя суммировать или удалять.",both);}
            for(int i=0;i<6;i++){
                String raw=r.cell(i+3);if(clean(raw)==null)continue;BigDecimal qty=number(raw);var source=ref("inventory_transit","Лист4",cell(i+3,r.number()));
                if(qty==null){d.issue("INVALID_REPORTED_INBOUND","error",item,"Нечисловое количество партии: "+raw,source);continue;}
                d.reportedInbound.add(new ReportedInbound("iek_inbound-"+r.number()+"-"+i,item.id(),qty,null,dates[i],labels[i],source));
                if(qty.signum()<0)d.issue("INVALID_REPORTED_INBOUND","error",item,"Отрицательное исходное количество партии: "+raw,source);
            }
        });
    }
    private void seasonality(Data d,byte[] bytes){
        BigDecimal[] rates=new BigDecimal[12];
        reader.read(bytes,"Сезонность",r->{if(r.number()>=11&&r.number()<=22){BigDecimal v=number(r.cell(11));if(v!=null&&v.signum()>0)rates[r.number()-11]=v.divide(BigDecimal.valueOf(YearMonth.of(2025,r.number()-10).lengthOfMonth()),MC);}});
        var refs=ref("seasonality","Сезонность","L11:L22");
        if(Arrays.stream(rates).anyMatch(Objects::isNull)){d.issue("INVALID_SEASONAL_PROFILE","error",null,"Не найдены 12 положительных коэффициентов сезонности IEK.",refs);return;}
        BigDecimal avg=Arrays.stream(rates).reduce(BigDecimal.ZERO,BigDecimal::add).divide(BigDecimal.valueOf(12),MC);
        d.seasonality.add(new Seasonality("IEK_PROVIDED","supplier","IEK","daily_rate",Arrays.stream(rates).map(v->v.divide(avg,MC)).toList(),refs));
        d.issue("SEASONAL_PROFILE_INTERPRETATION","warning",null,"Профиль IEK L11:L22 переведён в дневную скорость по длинам месяцев 2025 года. Показатель поставщика может быть выручкой и включает прогноз конца 2026 года; применимость к SKU требует проверки. Не использовать для исторического backtest до даты формирования.",refs);
    }
}
