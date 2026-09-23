package kz.uitech;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.ss.util.CellReference;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Reads cached cell values with SAX; formulas are never executed. */
public final class XlsxReader {
    public record Row(int number,Map<Integer,String> values) {public String cell(int column){return values.get(column);}}
    public void read(byte[] bytes,String sheetName,Consumer<Row> consume) {
        // Opening an InputStream makes POI inflate all ZIP entries into heap, even with SAX.
        // A read-only file package keeps large worksheet XML streamed from the compressed file.
        Path temp;
        try{temp=Files.createTempFile("uitech-xlsx-",".xlsx");}catch(IOException e){throw new IllegalStateException(e);}
        try{Files.write(temp,bytes);readFile(temp,sheetName,consume);}
        catch(IOException e){throw new IllegalStateException(e);}
        finally{try{Files.deleteIfExists(temp);}catch(IOException e){throw new IllegalStateException(e);}}
    }
    private void readFile(Path file,String sheetName,Consumer<Row> consume) {
        try(OPCPackage pkg=OPCPackage.open(file.toFile(),PackageAccess.READ)) {
            XSSFReader reader=new XSSFReader(pkg);SharedStrings shared=reader.getSharedStringsTable();
            XSSFReader.SheetIterator it=(XSSFReader.SheetIterator)reader.getSheetsData();
            while(it.hasNext())try(InputStream sheet=it.next()) {
                if(!sheetName.equals(it.getSheetName()))continue;
                XMLReader xml=XMLHelper.newXMLReader();
                xml.setContentHandler(new DefaultHandler() {
                    Map<Integer,String> values;int row,column;String type;StringBuilder value;boolean reading;
                    @Override public void startElement(String uri,String local,String name,Attributes a) {
                        if(name.equals("row")){row=Integer.parseInt(a.getValue("r"));values=new LinkedHashMap<>();}
                        if(name.equals("c")){String ref=a.getValue("r");column=new CellReference(ref).getCol();type=a.getValue("t");value=new StringBuilder();}
                        if(name.equals("v")||name.equals("t"))reading=true;
                    }
                    @Override public void characters(char[] c,int s,int n){if(reading)value.append(c,s,n);}
                    @Override public void endElement(String uri,String local,String name) {
                        if(name.equals("v")||name.equals("t"))reading=false;
                        if(name.equals("c")) {
                            String v=value.toString();
                            if("s".equals(type)&&!v.isEmpty())v=shared.getItemAt(Integer.parseInt(v)).getString();
                            if(!v.isEmpty())values.put(column,v);
                        }
                        if(name.equals("row"))consume.accept(new Row(row,values));
                    }
                });
                xml.parse(new InputSource(sheet));return;
            }
            throw ApiException.invalid("В книге не найден лист "+sheetName);
        } catch(ApiException e){throw e;} catch(Exception e){throw new ApiException(422,"INVALID_XLSX","Не удалось прочитать XLSX: "+e.getClass().getSimpleName());}
    }
}
