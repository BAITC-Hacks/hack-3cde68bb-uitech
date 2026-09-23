package kz.uitech;

import static kz.uitech.Model.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;
import java.util.function.UnaryOperator;

/** Dataset inserts and calculation mutations are atomic within each storage implementation. */
public interface StorageRepository {
    boolean insertDataset(String id,Dataset data,ObjectNode summary,Instant createdAt);
    default boolean insertDatasetWithFiles(String id,Dataset data,ObjectNode summary,Instant createdAt,java.util.List<SourceFile> files){
        boolean added=insertDataset(id,data,summary,createdAt);attachFiles(id,files);return added;
    }
    default boolean insertCorrectedDataset(String id,Dataset data,ObjectNode summary,Instant createdAt,java.util.List<SourceFile> files,String parentId){
        return insertDatasetWithFiles(id,data,summary,createdAt,files);
    }
    Dataset dataset(String id);
    default Product product(String id,String productId){return dataset(id).products().stream().filter(p->p.productId().equals(productId)).findFirst().orElseThrow(()->new ApiException(404,"NOT_FOUND","Товар не найден"));}
    default void checkHealth(){}
    DatasetReview review(String id);
    ObjectNode summary(String id);
    ArrayNode summaries();
    boolean insertCalculation(ObjectNode calculation);
    ObjectNode calculation(String id);
    ObjectNode mutateCalculation(String id,UnaryOperator<ObjectNode> mutation);
    String kind();
    void attachFiles(String datasetId,java.util.List<SourceFile> files);
    java.util.List<SourceFile> files(String datasetId);
}
