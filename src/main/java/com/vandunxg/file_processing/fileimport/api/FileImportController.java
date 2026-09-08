package com.vandunxg.file_processing.fileimport.api;

import java.beans.PropertyEditorSupport;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.vandunxg.common.models.dto.PageDTO;
import com.vandunxg.common.models.dto.response.PagingResponse;
import com.vandunxg.common.models.dto.response.Response;
import com.vandunxg.common.models.validator.ValidatePaging;
import com.vandunxg.file_processing.configuration.security.AuthenticatedUser;
import com.vandunxg.file_processing.fileimport.api.dto.request.ProcessingJobSearchRequest;
import com.vandunxg.file_processing.fileimport.api.mapper.ProcessingJobWebMapper;
import com.vandunxg.file_processing.fileimport.application.command.UploadFileCommand;
import com.vandunxg.file_processing.fileimport.application.exception.FileImportErrorCode;
import com.vandunxg.file_processing.fileimport.application.exception.FileImportException;
import com.vandunxg.file_processing.fileimport.application.result.ProcessingJobProgressResult;
import com.vandunxg.file_processing.fileimport.application.result.ProcessingJobResult;
import com.vandunxg.file_processing.fileimport.application.result.ProcessingJobSummaryResult;
import com.vandunxg.file_processing.fileimport.application.result.UploadFileResult;
import com.vandunxg.file_processing.fileimport.application.service.FileImportCommandService;
import com.vandunxg.file_processing.fileimport.application.service.ProcessingJobCommandService;
import com.vandunxg.file_processing.fileimport.application.service.ProcessingJobQueryService;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("${app.api.prefix}/${app.api.version}/file-import")
@RequiredArgsConstructor
@Tag(
    name = "File import",
    description = "Bearer access token required. `all:manage` satisfies the `self_*` permissions.")
public class FileImportController {

  /**
   * Permissions that let a caller act on a resource somebody else owns.
   *
   * <p>The seeded operator role only ever grants {@code *:self_*}, so anyone holding one of these
   * is administrating rather than using their own data. Authorities in this application are {@code
   * resource:action} permissions, never {@code ROLE_*}.
   */
  private static final Set<String> CROSS_OWNER_PERMISSIONS = Set.of("all:manage", "job:manage");

  private final FileImportCommandService fileImportCommandService;
  private final ProcessingJobCommandService processingJobCommandService;
  private final ProcessingJobQueryService processingJobQueryService;
  private final ProcessingJobWebMapper processingJobWebMapper;

  /**
   * Reads a time filter as an ISO-8601 instant.
   *
   * <p>The application-wide editor reads epoch milliseconds, which is unusable in a hand-written
   * query string, so the time filters on this controller take the same shape as the ones on the
   * admin log endpoints.
   */
  @InitBinder
  void bindInstant(WebDataBinder binder) {
    binder.registerCustomEditor(
        Instant.class,
        new PropertyEditorSupport() {
          @Override
          public void setAsText(String text) {
            setValue(Instant.parse(text));
          }
        });
  }

  /**
   * Accepts a CSV and queues it.
   *
   * <p>Answers {@code 202}, not {@code 200}: the file is stored and validated during the request,
   * but its rows are processed afterwards by a worker, so the response describes a job to watch
   * rather than a finished import.
   */
  @PostMapping(
      // Both spellings: the previous mapping ended in a slash, so "/file-import/" is the published
      // upload URL, and Spring Boot 3 no longer matches a trailing slash implicitly.
      value = {"", "/"},
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.ACCEPTED)
  @PreAuthorize("hasPermission(null, 'file:self_create')")
  public Response<UploadFileResult> upload(
      @RequestPart("file") List<MultipartFile> files,
      @AuthenticationPrincipal AuthenticatedUser principal) {
    MultipartFile file = requireExactlyOneCsv(files);
    try (var inputStream = file.getInputStream()) {
      return Response.of(
          fileImportCommandService.upload(
              new UploadFileCommand(
                  principal.userId(),
                  file.getOriginalFilename(),
                  MediaType.APPLICATION_OCTET_STREAM_VALUE,
                  file.getSize(),
                  inputStream)));
    } catch (IOException exception) {
      throw new FileImportException(FileImportErrorCode.FILE_IMPORT_STORAGE_UNAVAILABLE, exception);
    }
  }

  /**
   * Lists jobs, newest first.
   *
   * <p>The owner filter only widens the result for a caller who may act on any owner; for everyone
   * else the application replaces it with the caller's own id, so it can never be used to read
   * somebody else's list.
   */
  @GetMapping("/jobs")
  @PreAuthorize("hasPermission(null, 'job:self_read')")
  public PagingResponse<ProcessingJobSummaryResult> listJobs(
      @ValidatePaging(sortModel = ProcessingJob.class) ProcessingJobSearchRequest request,
      @AuthenticationPrincipal AuthenticatedUser principal) {
    PageDTO<ProcessingJobSummaryResult> page =
        processingJobQueryService.list(
            processingJobWebMapper.toQuery(request),
            principal.userId(),
            canActOnAnyOwner(principal));
    return new PagingResponse<>(page);
  }

  @GetMapping("/jobs/{jobId}")
  @PreAuthorize("hasPermission(null, 'job:self_read')")
  public Response<ProcessingJobResult> getJob(
      @PathVariable UUID jobId, @AuthenticationPrincipal AuthenticatedUser principal) {
    return Response.of(
        processingJobQueryService.get(jobId, principal.userId(), canActOnAnyOwner(principal)));
  }

  /** Narrower than the detail view: counters and timing only, for polling while a job runs. */
  @GetMapping("/jobs/{jobId}/progress")
  @PreAuthorize("hasPermission(null, 'job:self_read')")
  public Response<ProcessingJobProgressResult> getProgress(
      @PathVariable UUID jobId, @AuthenticationPrincipal AuthenticatedUser principal) {
    return Response.of(
        processingJobQueryService.getProgress(
            jobId, principal.userId(), canActOnAnyOwner(principal)));
  }

  /** Cooperative: a running job stops at its next safe point, so this only records the request. */
  @PostMapping("/jobs/{jobId}/cancel")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @PreAuthorize("hasPermission(null, 'job:self_update')")
  public Response<Void> cancel(
      @PathVariable UUID jobId, @AuthenticationPrincipal AuthenticatedUser principal) {
    processingJobCommandService.requestCancellation(
        jobId, principal.userId(), canActOnAnyOwner(principal));
    return Response.of(null);
  }

  @PostMapping("/jobs/{jobId}/retry")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @PreAuthorize("hasPermission(null, 'job:self_update')")
  public Response<Void> retry(
      @PathVariable UUID jobId, @AuthenticationPrincipal AuthenticatedUser principal) {
    processingJobCommandService.requestRetry(
        jobId, principal.userId(), canActOnAnyOwner(principal));
    return Response.of(null);
  }

  @GetMapping(value = "/jobs/{jobId}/error-report", produces = "text/csv")
  @PreAuthorize("hasPermission(null, 'report:self_read')")
  public ResponseEntity<StreamingResponseBody> downloadErrorReport(
      @PathVariable UUID jobId, @AuthenticationPrincipal AuthenticatedUser principal) {
    return streamed(
        processingJobQueryService.openErrorReport(
            jobId, principal.userId(), canActOnAnyOwner(principal)));
  }

  /** Kept so existing clients holding a file id keep working; delegates to the job-scoped query. */
  @GetMapping(value = "/{fileId}/error-report", produces = "text/csv")
  @PreAuthorize("hasPermission(null, 'report:self_read')")
  public ResponseEntity<StreamingResponseBody> downloadErrorReportByFile(
      @PathVariable UUID fileId, @AuthenticationPrincipal AuthenticatedUser principal) {
    return streamed(
        processingJobQueryService.openErrorReportByFile(
            fileId, principal.userId(), canActOnAnyOwner(principal)));
  }

  private static ResponseEntity<StreamingResponseBody> streamed(InputStream report) {
    StreamingResponseBody body =
        output -> {
          try (report) {
            report.transferTo(output);
          }
        };
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/csv"))
        .header("Content-Disposition", "attachment; filename=customer-import-errors.csv")
        .body(body);
  }

  private static MultipartFile requireExactlyOneCsv(List<MultipartFile> files) {
    if (files.isEmpty()) {
      throw new FileImportException(FileImportErrorCode.FILE_IMPORT_FILE_REQUIRED);
    }
    if (files.size() != 1) {
      throw new FileImportException(FileImportErrorCode.FILE_IMPORT_ONLY_ONE_FILE_ALLOWED);
    }
    MultipartFile file = files.getFirst();
    if (file.isEmpty()) {
      throw new FileImportException(FileImportErrorCode.FILE_IMPORT_EMPTY_FILE);
    }
    String filename = file.getOriginalFilename();
    if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
      throw new FileImportException(FileImportErrorCode.FILE_IMPORT_UNSUPPORTED_FILE_TYPE);
    }
    return file;
  }

  private static boolean canActOnAnyOwner(AuthenticatedUser principal) {
    return principal.authorities().stream()
        .map(GrantedAuthority::getAuthority)
        .anyMatch(CROSS_OWNER_PERMISSIONS::contains);
  }
}
