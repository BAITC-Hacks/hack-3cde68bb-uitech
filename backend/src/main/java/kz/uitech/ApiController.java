package kz.uitech;

import static kz.uitech.Model.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
    private final ObjectMapper mapper;private final LocalStore store;private final CalculationEngine engine;private final SystemeImporter importer;private final IekImporter iek;private final FileStorage fileStorage;
    private final SourceCorrections corrections;
    public ApiController(ObjectMapper mapper,LocalStore store,CalculationEngine engine,SystemeImporter importer,IekImporter iek,FileStorage fileStorage,SourceCorrections corrections){this.mapper=mapper;this.store=store;this.engine=engine;this.importer=importer;this.iek=iek;this.fileStorage=fileStorage;this.corrections=corrections;}
    @PostMapping("/datasets/{id}/products/{productId}/corrections")
    public ResponseEntity<ObjectNode> correct(@PathVariable String id,@PathVariable String productId,@RequestBody ProductCorrection change)throws IOException {
        return saved(corrections.correct(id,productId,change));
    }
    @GetMapping("/health") public Map<String,String> health(){store.checkHealth();return Map.of("status","ok","api_version","1.0","storage",store.storageKind());}
    @GetMapping("/datasets") public JsonNode datasets()throws IOException{return store.listDatasets();}
    @PostMapping("/datasets") public ResponseEntity<ObjectNode> dataset(@RequestBody Dataset dataset)throws IOException{return saved(store.putDataset(dataset));}
    @GetMapping("/datasets/{id}") public ObjectNode summary(@PathVariable String id){return store.summary(id);}
    @GetMapping("/datasets/{id}/data") public Dataset data(@PathVariable String id){return store.dataset(id);}
    @GetMapping("/datasets/{id}/review") public DatasetReview review(@PathVariable String id){return store.review(id);}
    @GetMapping("/datasets/{id}/files") public List<SourceFile> files(@PathVariable String id){return store.files(id);}
    @GetMapping("/datasets/{id}/files/{sha256}") public ResponseEntity<org.springframework.core.io.Resource> file(@PathVariable String id,@PathVariable String sha256)throws IOException {
        SourceFile file=store.files(id).stream().filter(f->f.sha256().equals(sha256)).findFirst().orElseThrow(()->new ApiException(404,"NOT_FOUND","Файл не найден в наборе"));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.fileName(),StandardCharsets.UTF_8).build().toString()).body(fileStorage.resource(file.objectKey()));
    }
    @PostMapping(value="/datasets/import",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ObjectNode> upload(@RequestParam String manifest,@RequestParam Map<String,MultipartFile> parts)throws IOException {
        ImportManifest input;
        try{input=mapper.readValue(manifest,ImportManifest.class);}catch(Exception e){throw new ApiException(400,"INVALID_MANIFEST","Некорректный JSON manifest");}
        Validation.require(input!=null&&input.files()!=null&&!input.files().isEmpty(),"Нужны файлы manifest");
        Set<String> suppliers=new HashSet<>();
        for(ManifestFile file:input.files()){Validation.require(file!=null,"Пустая строка manifest");suppliers.add(file.supplierId());}
        Validation.require(suppliers.size()==1,"За один импорт передавайте файлы одного поставщика");
        String supplier=suppliers.iterator().next();
        Validation.require("SYSTEME".equals(supplier)||"IEK".equals(supplier),"Поддерживаются поставщики SYSTEME и IEK");
        Map<String,SystemeImporter.FileInput> files=new HashMap<>();
        for(var part:parts.entrySet()) {MultipartFile f=part.getValue();String name=Objects.toString(f.getOriginalFilename(),"upload.xlsx").replace('\\','/');name=name.substring(name.lastIndexOf('/')+1);files.put(part.getKey(),new SystemeImporter.FileInput(name,f.getBytes()));}
        Dataset dataset="IEK".equals(supplier)?iek.parse(input,files):importer.parse(input,files);Validation.dataset(dataset);
        List<SourceFile> savedFiles=new ArrayList<>();
        for(ManifestFile entry:input.files()){
            var file=files.get(entry.partName());var object=fileStorage.put(file.bytes());
            savedFiles.add(new SourceFile(entry.role(),file.name(),object.objectKey(),object.sha256(),object.byteSize()));
        }
        return saved(store.putDataset(dataset,savedFiles));
    }
    private ResponseEntity<ObjectNode> saved(ObjectNode summary){return ResponseEntity.status(summary.path("reused").asBoolean()?200:201).body(summary);}
    @PostMapping("/calculations") public ResponseEntity<ObjectNode> calculate(@RequestBody CalculationRequest request)throws IOException {
        Validation.calculation(request);return ResponseEntity.status(201).body(store.addCalculation(engine.calculate(store.dataset(request.datasetId()),request)));
    }
    @GetMapping("/calculations/{id}") public ObjectNode calculation(@PathVariable String id){return store.calculation(id);}
    @GetMapping("/calculations/{id}/items/{itemId}") public ObjectNode item(@PathVariable String id,@PathVariable String itemId){return store.item(id,itemId);}
    @PatchMapping("/calculations/{id}/items/{itemId}") public ObjectNode patch(@PathVariable String id,@PathVariable String itemId,@RequestBody JsonNode request)throws IOException{return store.patch(id,itemId,request);}
    @PostMapping("/calculations/{id}/approve") public ObjectNode approve(@PathVariable String id,@RequestBody JsonNode request)throws IOException{return store.approve(id,request);}
    @GetMapping("/calculations/{id}/export") public ResponseEntity<byte[]> export(@PathVariable String id,@RequestParam("supplier_id") String supplier,@RequestParam String format,@RequestParam int revision) {
        if(!format.equals("csv"))throw ApiException.invalid("Сейчас поддерживается format=csv");
        String csv=store.csv(id,supplier,revision);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+id+"-"+supplier.replaceAll("[^A-Za-z0-9_-]","_")+"-r"+revision+".csv\"")
                .contentType(new MediaType("text","csv",StandardCharsets.UTF_8)).body(csv.getBytes(StandardCharsets.UTF_8));
    }
}
