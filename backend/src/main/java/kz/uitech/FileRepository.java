package kz.uitech;

import static kz.uitech.Model.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Explicit fallback for demos without PostgreSQL; a single process owns this directory. */
@Repository @Profile("file")
public class FileRepository implements StorageRepository {
    private final ObjectMapper mapper;private final Path directory;
    public FileRepository(ObjectMapper mapper,@Value("${uitech.data-dir}") String directory){
        this.mapper=mapper;this.directory=Path.of(directory).toAbsolutePath().normalize();
        try{Files.createDirectories(this.directory);}catch(IOException e){throw new IllegalStateException(e);}
    }
    private Path path(String id){LocalStore.validateId(id,id.startsWith("ds_")?"ds_":"calc_");return directory.resolve(id+".json");}
    public synchronized boolean insertDataset(String id,Dataset d,ObjectNode summary,Instant createdAt){
        if(Files.exists(path(id)))return false;write(id,d);
        try{Files.setLastModifiedTime(path(id),java.nio.file.attribute.FileTime.from(createdAt));}catch(IOException e){throw new IllegalStateException(e);}return true;
    }
    public Dataset dataset(String id){return read(id,Dataset.class);}
    public DatasetReview review(String id){return DatasetReview.from(dataset(id));}
    public ObjectNode summary(String id){
        Dataset d=dataset(id);try{return LocalStore.datasetSummary(mapper,id,d,Files.getLastModifiedTime(path(id)).toInstant());}catch(IOException e){throw new IllegalStateException(e);}
    }
    public ArrayNode summaries(){
        List<ObjectNode> values=new ArrayList<>();
        try(var files=Files.list(directory)){for(Path p:files.filter(p->p.getFileName().toString().matches("ds_[a-f0-9]{64}\\.json")).toList())values.add(summary(p.getFileName().toString().replace(".json","")));}
        catch(IOException e){throw new IllegalStateException(e);}
        values.sort(Comparator.comparing((ObjectNode n)->n.path("created_at").asText()).reversed());ArrayNode result=mapper.createArrayNode();values.forEach(result::add);return result;
    }
    public synchronized boolean insertCalculation(ObjectNode c){String id=c.path("calculation_id").asText();if(Files.exists(path(id)))return false;write(id,c);return true;}
    public ObjectNode calculation(String id){return read(id,ObjectNode.class);}
    public synchronized ObjectNode mutateCalculation(String id,UnaryOperator<ObjectNode> mutation){ObjectNode result=mutation.apply(calculation(id));write(id,result);return result.deepCopy();}
    public String kind(){return "file";}
    public synchronized void attachFiles(String id,List<SourceFile> files){
        List<SourceFile> all=new ArrayList<>(files(id));for(SourceFile f:files)if(all.stream().noneMatch(old->old.partName().equals(f.partName())&&old.sha256().equals(f.sha256())))all.add(f);
        try{Path dir=directory.resolve("source-files");Files.createDirectories(dir);Path target=dir.resolve(id+".json"),temp=Files.createTempFile(dir,"files-",".tmp");
            try{mapper.writeValue(temp.toFile(),all);Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}
        }catch(IOException e){throw new IllegalStateException(e);}
    }
    public List<SourceFile> files(String id){LocalStore.validateId(id,"ds_");Path file=directory.resolve("source-files").resolve(id+".json");if(!Files.exists(file))return List.of();
        try{return mapper.readValue(file.toFile(),mapper.getTypeFactory().constructCollectionType(List.class,SourceFile.class));}catch(IOException e){throw new IllegalStateException(e);}
    }
    private <T>T read(String id,Class<T> type){
        Path file=path(id);if(!Files.isRegularFile(file))throw new ApiException(404,"NOT_FOUND","Объект не найден");
        try{return mapper.readValue(file.toFile(),type);}catch(IOException e){throw new IllegalStateException(e);}
    }
    private void write(String id,Object data){
        try{Path temp=Files.createTempFile(directory,"write-",".tmp");
            try{mapper.writeValue(temp.toFile(),data);try{Files.move(temp,path(id),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temp,path(id),StandardCopyOption.REPLACE_EXISTING);}}
            finally{Files.deleteIfExists(temp);}
        }catch(IOException e){throw new IllegalStateException(e);}
    }
}
