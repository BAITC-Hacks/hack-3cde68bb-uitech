package kz.uitech;

import java.io.IOException;
import org.springframework.core.io.Resource;

/** Object keys are backend-owned; an S3 adapter can replace the local implementation. */
public interface FileStorage {
    record Stored(String objectKey,String sha256,long byteSize){}
    Stored put(byte[] bytes)throws IOException;
    Resource resource(String objectKey)throws IOException;
}
