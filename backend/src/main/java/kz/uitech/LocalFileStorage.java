package kz.uitech;

import java.io.IOException;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class LocalFileStorage implements FileStorage {
    private final Path root;
    public LocalFileStorage(@Value("${uitech.files-dir:../.local/files}") String directory)throws IOException{root=Path.of(directory).toAbsolutePath().normalize();Files.createDirectories(root);}
    public Stored put(byte[] bytes)throws IOException {
        String hash=LocalStore.hash(bytes),key=hash.substring(0,2)+"/"+hash+".bin";Path target=resolve(key);
        Files.createDirectories(target.getParent());
        if(!Files.exists(target)){
            Path temp=Files.createTempFile(target.getParent(),"upload-",".tmp");
            try{Files.write(temp,bytes);try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){try{Files.move(temp,target);}catch(FileAlreadyExistsException exists){/* Same content hash was saved concurrently. */}}}
            finally{Files.deleteIfExists(temp);}
        }
        return new Stored(key,hash,bytes.length);
    }
    private Path resolve(String key){
        if(key==null||!key.matches("[a-f0-9]{2}/[a-f0-9]{64}\\.bin"))throw new ApiException(404,"NOT_FOUND","Файл не найден");
        Path path=root.resolve(key).normalize();if(!path.startsWith(root))throw new ApiException(404,"NOT_FOUND","Файл не найден");return path;
    }
    public Resource resource(String key)throws IOException{Path path=resolve(key);if(!Files.isRegularFile(path))throw new ApiException(404,"NOT_FOUND","Файл не найден");return new FileSystemResource(path);}
}
