package pe.foodly.radar.interfaces.rest;

import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import pe.foodly.radar.application.exceptions.InvalidRadarSearchException;
import pe.foodly.radar.interfaces.rest.resources.ErrorResponse;

/** Convierte todos los errores en el formato uniforme { status, error, message, path, timestamp }. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    static final String RADAR_QUERY_ERROR = "No se pudo procesar la consulta del radar: ";

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidRadarSearchException.class)
    public ResponseEntity<Object> handleInvalidSearch(InvalidRadarSearchException ex, WebRequest request) {
        return error(HttpStatus.BAD_REQUEST, RADAR_QUERY_ERROR + ex.getMessage(), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = Stream.concat(
                        ex.getBindingResult().getFieldErrors().stream().map(e -> e.getDefaultMessage()),
                        ex.getBindingResult().getGlobalErrors().stream().map(e -> e.getDefaultMessage()))
                .distinct()
                .sorted()
                .reduce((a, b) -> a + "; " + b)
                .orElse("los datos enviados no son válidos");
        return error(HttpStatus.BAD_REQUEST, RADAR_QUERY_ERROR + detail, request);
    }

    /** No se devuelve el mensaje de Jackson para no filtrar detalles internos. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return error(HttpStatus.BAD_REQUEST,
                RADAR_QUERY_ERROR + "el cuerpo de la solicitud no es un JSON válido o tiene valores con formato incorrecto",
                request);
    }

    /** El resto de errores de Spring MVC (404, 405, 415...) también salen con el formato uniforme. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.valueOf(statusCode.value());
        return error(status, messageFor(status), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) throws Exception {
        // La seguridad tiene sus propios manejadores (401 y 403): no se convierten en 500.
        if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
            throw ex;
        }
        log.error("Error inesperado en {}", pathOf(request), ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, messageFor(HttpStatus.INTERNAL_SERVER_ERROR), request);
    }

    private static String messageFor(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "No se encontró el recurso solicitado.";
            case METHOD_NOT_ALLOWED -> "El método HTTP no está permitido para este recurso.";
            case NOT_ACCEPTABLE -> "El formato de respuesta solicitado no está disponible.";
            case UNSUPPORTED_MEDIA_TYPE -> "El tipo de contenido no es compatible. Use application/json.";
            case BAD_REQUEST -> RADAR_QUERY_ERROR + "la solicitud no es válida";
            case INTERNAL_SERVER_ERROR -> "Ocurrió un error interno. Intente nuevamente más tarde.";
            default -> "No se pudo atender la solicitud.";
        };
    }

    private static ResponseEntity<Object> error(HttpStatus status, String message, WebRequest request) {
        return ResponseEntity.status(status).body(ErrorResponse.of(status, message, pathOf(request)));
    }

    private static String pathOf(WebRequest request) {
        return request instanceof ServletWebRequest servletRequest ? servletRequest.getRequest().getRequestURI() : "";
    }
}
