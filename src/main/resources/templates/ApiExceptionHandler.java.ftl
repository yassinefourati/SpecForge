package [=pkg];
<#if style.problemDetail()>
<#-- STYLE-10: RFC 9457 problem details. Extending ResponseEntityExceptionHandler gives framework errors (unknown
     path, wrong method, ResponseStatusException, unreadable body, ...) the same shape. -->

import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
<#if options.proxy>
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
</#if>
<#if source.kind == "DATABASE">
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
</#if>
<#if source.engine == "SQLITE">
import org.springframework.orm.jpa.JpaSystemException;
import org.sqlite.SQLiteException;
</#if>
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
<#if options.validation>
import [=ns].validation.ConstraintViolationException;
</#if>

/**
 * Translates failures into RFC 9457 problem details ({@code application/problem+json}): {@code status},
 * {@code title}, {@code detail}, and for invalid input {@code errors: [{field, message}]}, where {@code field} is
 * the JSON property name.
 *
 * [=generatedNote]
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /** One invalid input value. */
    public record FieldProblem(String field, String message) {
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail notFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public ProblemDetail notImplemented(UnsupportedOperationException ex) {
        return problem(HttpStatus.NOT_IMPLEMENTED, ex.getMessage());
    }

<#if options.proxy>
    /** The upstream API answered with an error: pass its status, body and content type through unchanged. */
    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<byte[]> upstreamError(RestClientResponseException ex) {
        HttpHeaders headers = new HttpHeaders();
        HttpHeaders upstream = ex.getResponseHeaders();
        if (upstream != null) {
            if (upstream.getContentType() != null) {
                headers.setContentType(upstream.getContentType());
            }
            // getFirst / getValuesAsList exist on Spring 6 and 7 (HttpHeaders is no longer a Map in Spring 7)
            if (upstream.getFirst(HttpHeaders.WWW_AUTHENTICATE) != null) {
                headers.addAll(HttpHeaders.WWW_AUTHENTICATE, upstream.getValuesAsList(HttpHeaders.WWW_AUTHENTICATE));
            }
        }
        return ResponseEntity.status(ex.getStatusCode()).headers(headers).body(ex.getResponseBodyAsByteArray());
    }

    /** The upstream API could not be reached or answered with something unreadable. */
    @ExceptionHandler(RestClientException.class)
    public ProblemDetail upstreamUnavailable(RestClientException ex) {
        return problem(HttpStatus.BAD_GATEWAY, "The upstream API could not be reached or gave an unreadable answer");
    }

</#if>
<#if source.kind == "DATABASE">
    /** A database constraint rejected the change (duplicate key, missing reference, …). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail conflict(DataIntegrityViolationException ex) {
        return problem(HttpStatus.CONFLICT, "The change violates a database constraint");
    }

    /** Someone else changed the row since it was read (optimistic locking). */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail staleRow(ObjectOptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "The row was changed by someone else; read it again and retry");
    }

<#if source.engine == "SQLITE">
    /** Hibernate's SQLite dialect does not classify constraint errors; SQLite's own result code does. */
    @ExceptionHandler(JpaSystemException.class)
    public ProblemDetail sqlite(JpaSystemException ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLiteException s && s.getResultCode().name().startsWith("SQLITE_CONSTRAINT")) {
                return problem(HttpStatus.CONFLICT, "The change violates a database constraint");
            }
        }
        throw ex;
    }

</#if>
</#if>
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        List<FieldProblem> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldProblem(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return handleExceptionInternal(ex, invalid(errors), headers, status, request);
    }
<#if options.validation>

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail constraintViolation(ConstraintViolationException ex) {
        List<FieldProblem> errors = ex.getConstraintViolations().stream()
                .map(v -> new FieldProblem(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return invalid(errors);
    }
</#if>

    private static ProblemDetail invalid(List<FieldProblem> errors) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Validation failed");
        pd.setProperty("errors", errors);
        return pd;
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        return ProblemDetail.forStatusAndDetail(status, detail);
    }
}
<#else>

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
<#if options.proxy>
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
</#if>
<#if source.kind == "DATABASE">
import org.springframework.dao.DataIntegrityViolationException;
</#if>
<#if source.engine == "SQLITE">
import org.springframework.orm.jpa.JpaSystemException;
import org.sqlite.SQLiteException;
</#if>
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
<#if options.validation>
import [=ns].validation.ConstraintViolationException;
</#if>

/**
 * Translates common failures into JSON error bodies.
 *
 * [=generatedNote]
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(ResourceNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage(), null);
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<Map<String, Object>> notImplemented(UnsupportedOperationException ex) {
        return error(HttpStatus.NOT_IMPLEMENTED, ex.getMessage(), null);
    }

<#if options.proxy>
    /** The upstream API answered with an error: pass its status, body and content type through unchanged. */
    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<byte[]> upstreamError(RestClientResponseException ex) {
        HttpHeaders headers = new HttpHeaders();
        HttpHeaders upstream = ex.getResponseHeaders();
        if (upstream != null) {
            if (upstream.getContentType() != null) {
                headers.setContentType(upstream.getContentType());
            }
            // getFirst / getValuesAsList exist on Spring 6 and 7 (HttpHeaders is no longer a Map in Spring 7)
            if (upstream.getFirst(HttpHeaders.WWW_AUTHENTICATE) != null) {
                headers.addAll(HttpHeaders.WWW_AUTHENTICATE, upstream.getValuesAsList(HttpHeaders.WWW_AUTHENTICATE));
            }
        }
        return ResponseEntity.status(ex.getStatusCode()).headers(headers).body(ex.getResponseBodyAsByteArray());
    }

    /** The upstream API could not be reached or answered with something unreadable. */
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Map<String, Object>> upstreamUnavailable(RestClientException ex) {
        return error(HttpStatus.BAD_GATEWAY, "The upstream API could not be reached or gave an unreadable answer", null);
    }

</#if>
<#if source.kind == "DATABASE">
    /** A database constraint rejected the change (duplicate key, missing reference, …). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> conflict(DataIntegrityViolationException ex) {
        return error(HttpStatus.CONFLICT, "The change violates a database constraint", null);
    }

<#if source.engine == "SQLITE">
    /** Hibernate's SQLite dialect does not classify constraint errors; SQLite's own result code does. */
    @ExceptionHandler(JpaSystemException.class)
    public ResponseEntity<Map<String, Object>> sqlite(JpaSystemException ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLiteException s && s.getResultCode().name().startsWith("SQLITE_CONSTRAINT")) {
                return error(HttpStatus.CONFLICT, "The change violates a database constraint", null);
            }
        }
        throw ex;
    }

</#if>
</#if>
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException ex) {
        return error(HttpStatus.BAD_REQUEST, "Malformed request body", null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException ex) {
        List<String> details = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            details.add(fe.getField() + ": " + fe.getDefaultMessage());
        }
        return error(HttpStatus.BAD_REQUEST, "Validation failed", details);
    }
<#if options.validation>

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> constraintViolation(ConstraintViolationException ex) {
        List<String> details = new ArrayList<>();
        ex.getConstraintViolations().forEach(v -> details.add(v.getPropertyPath() + ": " + v.getMessage()));
        return error(HttpStatus.BAD_REQUEST, "Validation failed", details);
    }
</#if>

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message, List<String> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        if (details != null && !details.isEmpty()) {
            body.put("details", details);
        }
        return ResponseEntity.status(status).body(body);
    }
}
</#if>
