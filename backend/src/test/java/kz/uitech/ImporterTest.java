package kz.uitech;

import static kz.uitech.Model.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

class ImporterTest {
    static String role(String name) {
        if(name.contains("MOQ"))return "moq";
        if(name.contains("Динамика"))return "sales_transactions";
        if(name.contains("Ежемесячные остатки"))return "stock_monthly";
        if(name.contains("Ежемесячные продажи"))return "sales_monthly";
        if(name.contains("Сезонность"))return "seasonality";
        if(name.contains("Товар в пути"))return "inventory_transit";
        throw new IllegalArgumentException(name);
    }
    static ImportManifest manifest(Set<String> roles){return new ImportManifest("Import test","real","Asia/Almaty",roles.stream().sorted().map(r->new ManifestFile(r,"SYSTEME",r)).toList());}
    @Test void sourceArchiveIsParsedWithoutInventingMissingFacts()throws Exception {
        String location=System.getProperty("uitech.systemeZip");Assumptions.assumeTrue(location!=null,"Real archive is optional; pass -Duitech.systemeZip=...");
        Map<String,SystemeImporter.FileInput> files=new HashMap<>();
        try(ZipFile zip=new ZipFile(location,Charset.forName("CP866"))) {
            for(var entry:Collections.list(zip.entries()))if(entry.getName().endsWith(".xlsx")) {
                String name=Path.of(entry.getName()).getFileName().toString();try(var input=zip.getInputStream(entry)){files.put(role(name),new SystemeImporter.FileInput(name,input.readAllBytes()));}
            }
        }
        Dataset d=new SystemeImporter().parse(manifest(files.keySet()),files);Validation.dataset(d);
        assertEquals(6,d.sources().size());assertTrue(d.products().size()>=701);assertTrue(d.sales().size()>76000);assertEquals(497,d.inventory().size());
        assertEquals(554*33,d.monthlySales().size());assertEquals(701*33,d.monthlyStock().size());assertEquals(1,d.seasonalityProfiles().size());
        assertTrue(d.sales().stream().allMatch(s->s.quantity().signum()>0&&s.customerId()==null));
        assertTrue(d.salesCoverage().stream().noneMatch(Coverage::complete));assertTrue(d.inboundCoverage().stream().noneMatch(InboundCoverage::complete));assertTrue(d.availability().isEmpty());
        assertTrue(d.issues().stream().anyMatch(i->i.code().equals("EXCLUDE_QUARANTINED_OPERATIONS")));assertTrue(d.monthlyStock().stream().anyMatch(v->v.quantity()==null));
        System.out.printf("REAL IMPORT: products=%d sales=%d snapshots=%d inbound=%d monthly_sales=%d monthly_stock=%d issues=%d%n",d.products().size(),d.sales().size(),d.inventory().size(),d.inbound().size(),d.monthlySales().size(),d.monthlyStock().size(),d.issues().size());
    }
    @Test void cachedNumericAndStringCellsAreReadAndBlankRemainsAbsent()throws Exception {
        byte[] bytes;
        try(XSSFWorkbook workbook=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            var sheet=workbook.createSheet("Test");var row=sheet.createRow(2);row.createCell(0).setCellValue("000001_");row.createCell(2).setCellValue(12.5);row.createCell(3).setCellFormula("C3*2");workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();workbook.write(out);bytes=out.toByteArray();
        }
        List<XlsxReader.Row> rows=new ArrayList<>();new XlsxReader().read(bytes,"Test",rows::add);assertEquals(1,rows.size());assertEquals(3,rows.get(0).number());assertEquals("000001_",rows.get(0).cell(0));assertNull(rows.get(0).cell(1));assertEquals("12.5",rows.get(0).cell(2));assertEquals("25.0",rows.get(0).cell(3));
        assertThrows(ApiException.class,()->new XlsxReader().read(bytes,"Missing",rows::add));
    }
    @Test void unknownSupplierAndIncompleteRoleSetAreRejected(){
        assertThrows(ApiException.class,()->new SystemeImporter().parse(new ImportManifest("Test","real","Asia/Almaty",List.of(new ManifestFile("f","IEK","moq"))),Map.of()));
        assertThrows(ApiException.class,()->new SystemeImporter().parse(manifest(Set.of("moq")),Map.of("moq",new SystemeImporter.FileInput("a.xlsx",new byte[0]))));
    }
}
