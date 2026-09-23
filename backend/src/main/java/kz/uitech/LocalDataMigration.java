package kz.uitech;

import static kz.uitech.Model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Explicit, restartable migration. Existing database records and source JSON are never overwritten. */
@Component @Profile("postgres") @ConditionalOnProperty(name="uitech.migrate-local",havingValue="true")
public class LocalDataMigration implements ApplicationRunner {
    private static final Logger LOG=LoggerFactory.getLogger(LocalDataMigration.class);
    private final ObjectMapper mapper;private final PostgresRepository repository;private final Path directory;
    public LocalDataMigration(ObjectMapper mapper,PostgresRepository repository,@Value("${uitech.data-dir}") String directory){this.mapper=mapper;this.repository=repository;this.directory=Path.of(directory);}
    public void run(ApplicationArguments args)throws Exception{migrate(directory);}
    public void migrate(Path source)throws Exception {
        if(!Files.isDirectory(source))throw new IllegalStateException("Каталог миграции не найден: "+source.toAbsolutePath());
        List<Path> files;try(var stream=Files.list(source)){files=stream.sorted().toList();}
        int datasets=0,calculations=0;
        FileRepository originals=new FileRepository(mapper,source.toString());
        for(Path file:files)if(file.getFileName().toString().matches("ds_[a-f0-9]{64}\\.json")){
            String id=file.getFileName().toString().replace(".json","");
            // Skip before deserializing a large file on repeated migration runs.
            try{repository.summary(id);repository.attachFiles(id,originals.files(id));repository.recordMigration(id,"dataset",fileHash(file),null);continue;}catch(ApiException e){if(e.status!=404)throw e;}
            Dataset d=mapper.readValue(file.toFile(),Dataset.class);Validation.dataset(d);Instant created=Files.getLastModifiedTime(file).toInstant();
            String reserialized=LocalStore.datasetId(mapper,d);
            // Older JSON-tree persistence normalized decimal formatting after hashing the DTO.
            // Preserve its public ID and record both source bytes and current serialization hash.
            if(!id.equals(reserialized))LOG.warn("Legacy dataset {} has a different reserialized hash; preserving public ID and recording source checksum",id);
            if(repository.insertDatasetWithFiles(id,d,LocalStore.datasetSummary(mapper,id,d,created),created,originals.files(id))){datasets++;LOG.info("Migrated dataset {}: {} products, {} sales",id,d.products().size(),d.sales().size());}
            repository.recordMigration(id,"dataset",fileHash(file),reserialized);
        }
        for(Path file:files)if(file.getFileName().toString().matches("calc_[a-f0-9]{32}\\.json")){
            ObjectNode c=mapper.readValue(file.toFile(),ObjectNode.class);
            String expected=file.getFileName().toString().replace(".json","");
            if(!expected.equals(c.path("calculation_id").asText()))throw new IllegalStateException("Calculation ID differs from migration filename");
            if(repository.insertCalculation(c,"MIGRATED"))calculations++;
            repository.recordMigration(expected,"calculation",fileHash(file),null);
        }
        LOG.info("Local migration complete: {} datasets and {} calculations added; source files retained",datasets,calculations);
    }
    private static String fileHash(Path file)throws Exception{
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        try(var input=new java.security.DigestInputStream(Files.newInputStream(file),digest)){input.transferTo(java.io.OutputStream.nullOutputStream());}
        return java.util.HexFormat.of().formatHex(digest.digest());
    }
}
