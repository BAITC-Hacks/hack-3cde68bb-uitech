package kz.uitech;

import static kz.uitech.Model.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.zip.ZipFile;

class IekImporterTest {
    static ImportManifest manifest(Set<String> roles) {
        return new ImportManifest("IEK test","real","Asia/Almaty",roles.stream().sorted().map(r->new ManifestFile(r,"IEK",r)).toList());
    }
    static void row(org.apache.poi.ss.usermodel.Sheet sheet,int number,Object... values) {
        var row=sheet.createRow(number-1);
        for(int i=0;i<values.length;i++)if(values[i]!=null) {
            var cell=row.createCell(i);if(values[i] instanceof Number n)cell.setCellValue(n.doubleValue());else cell.setCellValue(values[i].toString());
        }
    }
    static SystemeImporter.FileInput workbook(String role,String sheetName,java.util.function.Consumer<org.apache.poi.ss.usermodel.Sheet> fill)throws Exception {
        try(var workbook=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            fill.accept(workbook.createSheet(sheetName));workbook.write(out);return new SystemeImporter.FileInput(role+".xlsx",out.toByteArray());
        }
    }
    static Map<String,SystemeImporter.FileInput> files()throws Exception {
        Map<String,SystemeImporter.FileInput> files=new HashMap<>();
        files.put("moq",workbook("moq","Лист7",s->{
            row(s,1,"№","Код 1с","Артикул поставщика","Наименование","Мин. разр. к отгр.");
            row(s,2,1,"00001_","A1","Кабель",5);row(s,3,2,"00001_","A1","Кабель","#N/A");
        }));
        for(boolean stock:List.of(false,true)) {
            String role=stock?"stock_monthly":"sales_monthly";
            files.put(role,workbook(role,"Лист_1",s->{
                int start=stock?3:2;Object[] headers=new Object[start+33];headers[0]="Номенклатура";headers[start-1]="Номенклатура.Код";
                String[] months={"янв.","февр.","март","апр.","май","июнь","июль","авг.","сент.","окт.","нояб.","дек."};
                for(int i=0;i<33;i++)headers[start+i]=months[i%12]+" "+(2024+i/12);
                row(s,1,headers);if(stock){Object[] kind=new Object[start+1];kind[start]="нач. остаток";row(s,3,kind);}
                Object[] values=new Object[start+33];values[0]="Кабель";values[start-1]="00001_";if(stock)values[1]="м";
                values[start]=100;values[start+20]=-3;row(s,stock?4:3,values);
            }));
        }
        files.put("sales_transactions",workbook("sales_transactions","Лист_1",s->{
            row(s,1,"Дата","Номер","Документ","Код","Номенклатура","Ед.","Склад","Количество");
            row(s,2,"01.09.2026 0:00:00","N1","Расходная накладная","00001_","Кабель","м","Алматы",10);
            row(s,3,"02.09.2026 0:00:00","N2","Расходная накладная","00001_","Кабель","м","Алматы",-7);
            row(s,4,"03.09.2026 0:00:00","N3","Расходная накладная","00001_","Кабель","м","Алматы",null);
        }));
        files.put("inventory_transit",workbook("inventory_transit","Лист4",s->{
            Object[] headers=new Object[9];headers[0]="Код 1с";headers[1]="Артикул ИЭК";headers[2]="Наименование";
            String[] dates={"10.10.2026","15.10.2026","01.10.2026","30.09.2026","30.09.2026","30.09.2026"};
            for(int i=0;i<6;i++)headers[i+3]="Партия "+i+" (поступление до "+dates[i]+")";
            row(s,1,headers);row(s,2,"00001_","A1","Кабель",2,null,null,3);row(s,3,"00001_","A1","Кабель",2);
        }));
        files.put("seasonality",workbook("seasonality","Сезонность",s->{for(int i=11;i<=22;i++){Object[] values=new Object[12];values[11]=1;row(s,i,values);}}));
        return files;
    }
    @Test void uncertainUnitsDuplicatesAndMonthStartStockArePreservedWithoutPromotingThemToFacts()throws Exception {
        var files=files();Dataset d=new IekImporter().parse(manifest(files.keySet()),files);Validation.dataset(d);
        assertEquals(1,d.products().size());assertEquals("00001_",d.products().get(0).sku1c());assertEquals("м",d.products().get(0).stockUnit());
        assertNull(d.products().get(0).purchaseUnitFactor());assertNull(d.products().get(0).moqPurchaseQty());assertNull(d.products().get(0).packMultiplePurchaseQty());
        assertEquals(1,d.sales().size());assertEquals(2,d.issues().stream().filter(i->i.code().equals("EXCLUDE_QUARANTINED_OPERATIONS")).count());
        assertTrue(d.inventory().isEmpty());assertTrue(d.inbound().isEmpty());assertTrue(d.inboundCoverage().isEmpty());assertTrue(d.availability().isEmpty());
        assertEquals(3,d.reportedInbound().size());assertNull(d.reportedInbound().get(0).unit());
        assertEquals(LocalDate.of(2026,10,10),d.reportedInbound().get(0).arrivalDeadline());assertEquals(LocalDate.of(2026,9,30),d.reportedInbound().get(1).arrivalDeadline());
        assertEquals("D2",d.reportedInbound().get(0).sourceRefs().get(0).cellRange());
        assertEquals(2,d.reportedPurchaseRules().size());assertEquals("#N/A",d.reportedPurchaseRules().get(1).rawValue());assertNull(d.reportedPurchaseRules().get(1).reportedQuantity());
        assertTrue(d.issues().stream().anyMatch(i->i.code().equals("DUPLICATE_INBOUND_ROW")));assertTrue(d.issues().stream().anyMatch(i->i.code().equals("DUPLICATE_PURCHASE_RULE")));
        assertEquals(33,d.monthlyStock().size());assertEquals("reported_month_start_stock",d.monthlyStock().get(0).meaning());assertNull(d.monthlyStock().get(1).quantity());assertTrue(d.monthlyStock().get(20).quantity().signum()<0);
    }
    @Test void shipmentDatesAndTemplatesAreValidated()throws Exception {
        assertEquals(LocalDate.of(2026,10,10),IekImporter.deadline("РФ от 31 августа 2026 г. (поступление до 10.10.2026)"));
        assertThrows(ApiException.class,()->IekImporter.deadline("поступление до 31.09.2026"));assertThrows(ApiException.class,()->IekImporter.deadline("дата заказа 10.10.2026"));
        var files=files();files.put("moq",workbook("moq","Лист7",s->row(s,1,"Код","wrong template")));
        assertThrows(ApiException.class,()->new IekImporter().parse(manifest(files.keySet()),files));
    }
    @Test void reportedQuantitiesCannotReduceCalculatedOrdersAndMustReferenceKnownProducts()throws Exception {
        var f=CalculationTest.fixture("01_baseline");var d=f.withObject("dataset");
        var raw=d.putArray("reported_inbound").addObject().put("source_row_id","source-1").put("product_id","P1").put("reported_quantity",99999).put("shipment_label","Unverified batch");
        raw.putArray("source_refs").addObject().put("source_id","test").put("sheet","Sheet").put("cell_range","D2");
        assertEquals(150,CalculationTest.calculate(f).path("items").get(0).path("recommended_purchase_qty").asInt());
        raw.put("product_id","missing");assertThrows(ApiException.class,()->CalculationTest.calculate(f));
    }
    @Test void realIekArchiveReconcilesCountsAndPreservesUncertainInputs()throws Exception {
        String location=System.getProperty("uitech.iekZip");Assumptions.assumeTrue(location!=null,"Pass -Duitech.iekZip=... for the private archive");
        Map<String,SystemeImporter.FileInput> files=new HashMap<>();
        try(ZipFile zip=new ZipFile(location,Charset.forName("CP866"))) {
            for(var entry:Collections.list(zip.entries()))if(entry.getName().endsWith(".xlsx")) {
                String name=Path.of(entry.getName()).getFileName().toString(),role=name.startsWith("Путь")?"inventory_transit":ImporterTest.role(name);
                try(var input=zip.getInputStream(entry)){files.put(role,new SystemeImporter.FileInput(name,input.readAllBytes()));}
            }
        }
        Dataset d=new IekImporter().parse(manifest(files.keySet()),files);Validation.dataset(d);
        assertEquals(171470,d.sales().size());assertTrue(d.products().size()>=2853);assertEquals(1938,d.reportedPurchaseRules().size());assertTrue(d.reportedInbound().size()>=300);
        assertEquals(2463*33,d.monthlySales().size());assertEquals(2853*33,d.monthlyStock().size());assertEquals(1,d.seasonalityProfiles().size());
        assertEquals(133,d.issues().stream().filter(i->i.code().equals("EXCLUDE_QUARANTINED_OPERATIONS")).count());
        assertEquals(15,d.issues().stream().filter(i->i.code().equals("INVALID_MIN_SHIPMENT")).count());
        assertEquals(7,d.issues().stream().filter(i->i.code().equals("DUPLICATE_INBOUND_ROW")).count());
        assertEquals(1,d.issues().stream().filter(i->i.code().equals("DUPLICATE_PURCHASE_RULE")).count());
        assertTrue(d.inventory().isEmpty());assertTrue(d.inbound().isEmpty());assertTrue(d.salesCoverage().stream().noneMatch(Coverage::complete));
        System.out.printf("IEK IMPORT: products=%d sales=%d reported_inbound=%d purchase_rules=%d monthly_sales=%d monthly_stock=%d issues=%d%n",d.products().size(),d.sales().size(),d.reportedInbound().size(),d.reportedPurchaseRules().size(),d.monthlySales().size(),d.monthlyStock().size(),d.issues().size());
    }
}
