package kz.uitech;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class ApiErrors {
    private final Logger log=LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler(ApiException.class) public ResponseEntity<?> api(ApiException e){return error(e.status,e.code,e.getMessage());}
    @ExceptionHandler({HttpMessageNotReadableException.class,MissingServletRequestParameterException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> bad(Exception e){return error(400,"INVALID_REQUEST","Проверьте структуру JSON, типы полей и обязательные параметры");}
    @ExceptionHandler(MaxUploadSizeExceededException.class) public ResponseEntity<?> large(Exception e){return error(413,"UPLOAD_TOO_LARGE","Максимум 15 MB на файл и 40 MB на запрос");}
    @ExceptionHandler(Exception.class) public ResponseEntity<?> internal(Exception e){log.error("Backend operation failed",e);return error(500,"INTERNAL_ERROR","Не удалось выполнить операцию");}
    private ResponseEntity<?> error(int status,String code,String message){return ResponseEntity.status(status).body(Map.of("error",Map.of("code",code,"message",message,"fields",List.of(),"request_id",UUID.randomUUID().toString())));}
}
