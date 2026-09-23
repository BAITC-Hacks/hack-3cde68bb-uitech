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
    private final ObjectMapper mapper;private final LocalStore store;private final CalculationEngine engine;private final SystemeImporter importer;
    public ApiController(ObjectMapper mapper,LocalStore store,CalculationEngine engine,SystemeImporter importer){this.mapper=mapper;this.store=store;this.engine=engine;this.importer=importer;}
    @GetMapping("/health") public Map<String,String> health(){return Map.of("status","ok","api_version","1.0");}
    @GetMapping("/datasets") public JsonNode datasets()throws IOException{return store.listDatasets();}
    @PostMapping("/datasets") public ResponseEntity<ObjectNode> dataset(@RequestBody Dataset dataset)throws IOException{return saved(store.putDataset(dataset));}
    @GetMapping("/datasets/{id}") public ObjectNode summary(@PathVariable String id){return store.summary(id);}
    @GetMapping("/datasets/{id}/data") public Dataset data(@PathVariable String id){return store.dataset(id);}
    @PostMapping(value="/datasets/import",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ObjectNode> upload(@RequestParam String manifest,@RequestParam Map<String,MultipartFile> parts)throws IOException {
        ImportManifest input;
        try{input=mapper.readValue(manifest,ImportManifest.class);}catch(Exception e){throw new ApiException(400,"INVALID_MANIFEST","Некорректный JSON manifest");}
        Map<String,SystemeImporter.FileInput> files=new HashMap<>();
        for(var part:parts.entrySet()) {MultipartFile f=part.getValue();String name=Objects.toString(f.getOriginalFilename(),"upload.xlsx").replace('\\','/');name=name.substring(name.lastIndexOf('/')+1);files.put(part.getKey(),new SystemeImporter.FileInput(name,f.getBytes()));}
        return saved(store.putDataset(importer.parse(input,files)));
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
