package com.vandunxg.file_processing.fileimport.adapter.in.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.vandunxg.common.models.dto.response.Response;
import com.vandunxg.file_processing.configuration.security.AuthenticatedUser;
import com.vandunxg.file_processing.fileimport.application.command.UploadFileCommand;
import com.vandunxg.file_processing.fileimport.application.port.in.UploadFileUseCase;
import com.vandunxg.file_processing.fileimport.application.result.UploadFileResult;
import com.vandunxg.file_processing.fileimport.application.service.ErrorReportDownloadService;
import com.vandunxg.file_processing.fileimport.domain.exception.FileImportErrorCode;
import com.vandunxg.file_processing.fileimport.domain.exception.FileImportException;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("${app.api.prefix}/${app.api.version}/file-import/")
@RequiredArgsConstructor
@Tag(name = "File import", description = "Bearer access token required.")
public class FileImportController {

  private final UploadFileUseCase uploadFileUseCase;
  private final ErrorReportDownloadService errorReportDownloadService;

  @PostMapping(
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.OK)
  public Response<UploadFileResult> upload(
      @RequestPart("file") List<MultipartFile> files,
      @AuthenticationPrincipal AuthenticatedUser principal) {
    if (files.isEmpty()) {
      throw new FileImportException(FileImportErrorCode.FILE_REQUIRED);
    }
    if (files.size() != 1) {
      throw new FileImportException(FileImportErrorCode.ONLY_ONE_FILE_ALLOWED);
    }
    MultipartFile file = files.getFirst();
    if (file.isEmpty()) {
      throw new FileImportException(FileImportErrorCode.EMPTY_FILE);
    }
    String filename = file.getOriginalFilename();
    if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
      throw new FileImportException(FileImportErrorCode.UNSUPPORTED_FILE_TYPE);
    }
    try (var inputStream = file.getInputStream()) {
      return Response.of(
          uploadFileUseCase.upload(
              new UploadFileCommand(
                  principal.userId(),
                  file.getOriginalFilename(),
                  MediaType.APPLICATION_OCTET_STREAM_VALUE,
                  file.getSize(),
                  inputStream)));
    } catch (IOException exception) {
      throw new FileImportException(FileImportErrorCode.STORAGE_UNAVAILABLE, exception);
    }
  }

  @GetMapping(value = "{fileId}/error-report", produces = "text/csv")
  public ResponseEntity<StreamingResponseBody> downloadErrorReport(
      @PathVariable UUID fileId, @AuthenticationPrincipal AuthenticatedUser principal) {
    InputStream report = errorReportDownloadService.download(fileId, principal.userId());
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
}
