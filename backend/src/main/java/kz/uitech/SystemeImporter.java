package kz.uitech;

import static kz.uitech.Model.*;
import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

@Service
public class SystemeImporter {
    public static final String SUPPLIER="SYSTEME";
    public static final String WAREHOUSE="Алматы";
    private static final LocalDate SNAPSHOT=LocalDate.of(2026,9,22);
    private static final Set<String> ROLES=Set.of("sales_transactions","sales_monthly","stock_monthly","inventory_transit","moq","seasonality");
    private static final DateTimeFormatter SALE_DATE=DateTimeFormatter.ofPattern("dd.MM.yyyy H:mm:ss");
    private final XlsxReader reader=new XlsxReader();
    public record FileInput(String name,byte[] bytes) {}
    private static class Item {
        String id,code,article,name,unit="шт",category;BigDecimal pack;
        List<Ref> refs=new ArrayList<>();
        Product product(){return new Product(id,SUPPLIER,code,article,name,unit,unit,null,category,null,pack,refs);}
    }
    private String id(String code){return SUPPLIER+":"+code;}
    private static String clean(String s){return s==null?null:s.strip();}
    private static BigDecimal number(String s){if(s==null||s.isBlank()||s.startsWith("#"))return null;try{return new BigDecimal(s.replace("\u00a0","").replace(" ","").replace(',','.'));}catch(NumberFormatException e){return null;}}
    private static String col(int col,int row){return org.apache.poi.ss.util.CellReference.convertNumToColString(col)+row;}
    public Dataset parse(ImportManifest manifest,Map<String,FileInput> files) {
        Validation.require(manifest!=null,"manifest обязателен");
        Validation.text(manifest.name(),"name");Validation.require("real".equals(manifest.dataKind()),"Импорт XLSX имеет data_kind=real");
        Validation.require(manifest.files()!=null&&manifest.files().size()<=12&&!manifest.files().isEmpty(),"Нужно от 1 до 12 файлов");
        Map<String,ManifestFile> roles=new LinkedHashMap<>();
        for(ManifestFile f:manifest.files()) {
            Validation.require(f!=null,"Описание файла не может быть null");
            Validation.require(SUPPLIER.equals(f.supplierId()),"Этот адаптер поддерживает supplier_id=SYSTEME");
            Validation.require(f.role()!=null&&ROLES.contains(f.role())&&roles.putIfAbsent(f.role(),f)==null,"Неизвестная или повторная роль файла");
            Validation.require(files.containsKey(f.partName()),"Отсутствует часть "+f.partName());
        }
        Validation.require(roles.keySet().containsAll(ROLES),"Для импорта Systeme нужны все шесть ролей файлов");
        Map<String,Item> items=new LinkedHashMap<>();List<Source> sources=new ArrayList<>();List<Issue> issues=new ArrayList<>();
        List<Sale> sales=new ArrayList<>();List<Inventory> inventory=new ArrayList<>();List<Inbound> inbound=new ArrayList<>();
        List<MonthlyValue> monthlySales=new ArrayList<>(),monthlyStock=new ArrayList<>();List<Seasonality> seasonality=new ArrayList<>();
        Map<String,LocalDate> firstSale=new HashMap<>(),lastSale=new HashMap<>();Set<String> transitProducts=new HashSet<>();
        // Every role is processed, in a fixed order independent of upload order.
        for(String role:List.of("moq","sales_monthly","stock_monthly","sales_transactions","inventory_transit","seasonality")) {
            ManifestFile f=roles.get(role);FileInput file=files.get(f.partName());String source="systeme_"+role;
            sources.add(new Source(source,"xlsx",role,file.name(),"Адаптер Systeme Electric; исходные значения сохранены ссылками на ячейки."));
            String sheet=role.equals("inventory_transit")?"TDSheet":role.equals("seasonality")?"Лист1":"Лист_1";
            java.util.function.BiFunction<Integer,String,List<Ref>> ref=(row,range)->List.of(new Ref(source,sheet,range));
            Consumer<XlsxReader.Row> consume=switch(role) {
                case "moq" -> r->{
                    if(r.number()<3||r.cell(2)==null)return;String code=clean(r.cell(2));Item p=get(items,code,r.cell(1));p.article=clean(r.cell(3));p.pack=number(r.cell(4));p.refs.addAll(ref.apply(r.number(),"C"+r.number()+":E"+r.number()));
                    if(p.pack==null||p.pack.signum()<=0){p.pack=null;issues.add(issue("INVALID_PACK_MULTIPLE","warning",p.id,"Кратность отсутствует или некорректна",ref.apply(r.number(),"E"+r.number())));}
                };
                case "sales_monthly" -> r->{
                    if(r.number()<3||r.cell(1)==null)return;Item p=get(items,clean(r.cell(1)),r.cell(0));if(p.article==null)p.article=clean(r.cell(2));
                    for(int i=4;i<=36;i++)monthlySales.add(new MonthlyValue(p.id,"UNCONFIRMED",YearMonth.of(2024,1).plusMonths(i-4),number(r.cell(i)),"reported_monthly_sales",ref.apply(r.number(),col(i,r.number()))));
                    BigDecimal legacy=number(r.cell(3));if(legacy!=null&&p.pack!=null&&legacy.compareTo(p.pack)!=0)issues.add(issue("PACK_SOURCE_CONFLICT","warning",p.id,"Кратность месячной таблицы отличается от отдельного справочника; использован справочник MOQ.",ref.apply(r.number(),"D"+r.number())));
                };
                case "stock_monthly" -> r->{
                    if(r.number()<4||r.cell(2)==null)return;Item p=get(items,clean(r.cell(2)),r.cell(1));if(r.cell(3)!=null)p.unit=clean(r.cell(3));
                    for(int i=4;i<=36;i++)monthlyStock.add(new MonthlyValue(p.id,"UNCONFIRMED",YearMonth.of(2024,1).plusMonths(i-4),number(r.cell(i)),"reported_monthly_stock_unknown_timing",ref.apply(r.number(),col(i,r.number()))));
                };
                case "sales_transactions" -> r->{
                    if(r.number()<2||r.cell(3)==null)return;String code=clean(r.cell(3));Item p=get(items,code,r.cell(4));String warehouse=clean(r.cell(6));
                    if(r.cell(5)!=null)p.unit=clean(r.cell(5));List<Ref> refs=ref.apply(r.number(),"A"+r.number()+":H"+r.number());
                    BigDecimal q=number(r.cell(7));LocalDate day;
                    try{day=LocalDateTime.parse(r.cell(0),SALE_DATE).toLocalDate();}catch(Exception e){issues.add(issue("EXCLUDE_QUARANTINED_OPERATIONS","error",p.id,"Не распознана дата операции",refs));return;}
                    if(q==null||q.signum()<=0||r.cell(2)==null||!r.cell(2).startsWith("Расходная накладная")||warehouse==null||r.cell(1)==null) {
                        issues.add(issue("EXCLUDE_QUARANTINED_OPERATIONS","error",p.id,"Операция не включена в регулярные продажи. Исходное количество: "+Objects.toString(r.cell(7),"<пусто>")+"; тип: "+Objects.toString(r.cell(2),"<пусто>")+". Требуется политика возвратов и корректировок.",refs));return;
                    }
                    sales.add(new Sale(source+"-"+r.number(),p.id,warehouse,day,clean(r.cell(1)),null,"sale",q,refs));
                    String k=p.id+"|"+warehouse;firstSale.merge(k,day,(a,b)->a.isBefore(b)?a:b);lastSale.merge(k,day,(a,b)->a.isAfter(b)?a:b);
                };
                case "inventory_transit" -> r->{
                    if(r.number()<3||r.cell(2)==null)return;Item p=get(items,clean(r.cell(2)),r.cell(3));p.article=clean(r.cell(1));p.category=clean(r.cell(4));
                    List<Ref> refs=ref.apply(r.number(),"AX"+r.number()+":BC"+r.number());
                    // Column AZ is already free stock: never subtract AY again or add other warehouse columns.
                    inventory.add(new Inventory(p.id,WAREHOUSE,SNAPSHOT,number(r.cell(51)),refs));transitProducts.add(p.id);
                    BigDecimal q=number(r.cell(54));if(q!=null&&q.signum()>0)inbound.add(new Inbound(source+"-"+r.number(),p.id,WAREHOUSE,q,LocalDate.of(2026,9,24),"confirmed",ref.apply(r.number(),"BC"+r.number())));
                    if(q==null||q.signum()<0)issues.add(issue("INVALID_TRANSIT_QUANTITY","error",p.id,"Транзит не подтверждён числом",ref.apply(r.number(),"BC"+r.number())));
                    issues.add(issue("CONFIRM_SNAPSHOT_SCOPE","error",p.id,"Уточнить область AZ и момент снимка 22.09.2026; привязка к Алматы требует подтверждения.",refs));
                    issues.add(issue("CONFIRM_INBOUND_SCOPE","error",p.id,"Подтвердить, что колонка BC включает все открытые поставки для выбранного склада.",refs));
                };
                default -> r->{};
            };
            if(role.equals("seasonality")) {
                BigDecimal[] rates=new BigDecimal[12];
                reader.read(file.bytes(),sheet,r->{if(r.number()>=11&&r.number()<=22){BigDecimal v=number(r.cell(11));if(v!=null&&v.signum()>0)rates[r.number()-11]=v.divide(BigDecimal.valueOf(YearMonth.of(2025,r.number()-10).lengthOfMonth()),MathContext.DECIMAL128);}});
                if(Arrays.stream(rates).allMatch(Objects::nonNull)) {
                    BigDecimal avg=Arrays.stream(rates).reduce(BigDecimal.ZERO,BigDecimal::add).divide(BigDecimal.valueOf(12),MathContext.DECIMAL128);
                    seasonality.add(new Seasonality("SYSTEME_PROVIDED","supplier",SUPPLIER,"daily_rate",Arrays.stream(rates).map(v->v.divide(avg,MathContext.DECIMAL128)).toList(),List.of(new Ref(source,sheet,"L11:L22"))));
                } else issues.add(issue("INVALID_SEASONAL_PROFILE","error",null,"Не найдены 12 положительных коэффициентов сезонности",List.of(new Ref(source,sheet,"L11:L22"))));
            }else reader.read(file.bytes(),sheet,consume);
        }
        // Coverage records expose actual transaction coverage; old sparse correction-only years are not treated as complete.
        List<Coverage> coverage=new ArrayList<>();
        firstSale.forEach((k,first)->{int cut=k.lastIndexOf('|');String pid=k.substring(0,cut),warehouse=k.substring(cut+1);LocalDate start=first.isBefore(LocalDate.of(2025,1,1))?LocalDate.of(2025,1,1):first;coverage.add(new Coverage(pid,warehouse,start,lastSale.get(k).plusDays(1),false));});
        Map<String,BigDecimal> detailedMonths=new HashMap<>();
        for(Sale sale:sales)detailedMonths.merge(sale.productId()+"|"+YearMonth.from(sale.date()),sale.quantity(),BigDecimal::add);
        Map<String,Integer> mismatches=new LinkedHashMap<>();Map<String,List<Ref>> mismatchRefs=new HashMap<>();
        for(MonthlyValue value:monthlySales)if(value.quantity()!=null) {
            BigDecimal detailed=detailedMonths.get(value.productId()+"|"+value.month());
            if(detailed!=null&&detailed.compareTo(value.quantity())!=0){mismatches.merge(value.productId(),1,Integer::sum);mismatchRefs.computeIfAbsent(value.productId(),k->new ArrayList<>()).addAll(value.sourceRefs());}
        }
        mismatches.forEach((pid,count)->issues.add(issue("MONTHLY_SALES_MISMATCH","warning",pid,"Месячный отчёт расходится с положительными расходными накладными в "+count+" месяцах с данными в обоих источниках. Область сравнения не подтверждена; ряды не суммируются.",mismatchRefs.get(pid))));
        issues.add(issue("SALES_SCOPE_UNCONFIRMED","warning",null,"Детализация и месячный отчёт имеют неподтверждённую сопоставимость. Покрытие продаж оставлено неполным до сверки.",List.of()));
        issues.add(issue("CUSTOMER_ID_UNAVAILABLE","warning",null,"В детализации нет обезличенного ID клиента",List.of()));
        issues.add(issue("AVAILABILITY_UNAVAILABLE","warning",null,"Помесячные остатки не являются дневным календарём stockout",List.of()));
        issues.add(issue("LEGACY_FORMULA_13_MONTHS","warning",null,"В исходном TDSheet AP суммирует 13 месяцев, AQ делит на 12. Эти итоги не используются в прогнозе.",List.of(new Ref("systeme_inventory_transit","TDSheet","AP3:AQ3"))));
        issues.add(issue("SEASONAL_PROFILE_INTERPRETATION","warning",null,"Профиль поставщика L11:L22 переведён в относительную дневную скорость по длинам месяцев 2025 года; единицы исходного показателя и применимость к SKU требуют проверки. Не использовать этот профиль для исторического backtest до даты его формирования.",List.of(new Ref("systeme_seasonality","Лист1","L11:L22"))));
        List<InboundCoverage> inboundCoverage=transitProducts.stream().sorted().map(pid->new InboundCoverage(pid,WAREHOUSE,SNAPSHOT,false)).toList();
        return new Dataset("1.0",manifest.name(),"real",manifest.timezone(),List.of(new Supplier(SUPPLIER,"Systeme Electric")),items.values().stream().map(Item::product).toList(),coverage,sales,inventory,inbound,inboundCoverage,List.of(),seasonality,sources,issues,monthlySales,monthlyStock);
    }
    private Item get(Map<String,Item> items,String code,String name) {
        return items.computeIfAbsent(code,k->{Item p=new Item();p.id=id(k);p.code=k;p.name=name==null?k:clean(name);return p;});
    }
    private static Issue issue(String code,String severity,String product,String message,List<Ref> refs){return new Issue(code,severity,product,message,refs);}
}
