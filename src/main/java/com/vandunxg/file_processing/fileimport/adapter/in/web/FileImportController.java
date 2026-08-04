package com.vandunxg.file_processing.fileimport.adapter.in.web;

import com.vandunxg.common.models.dto.response.Response;
import com.vandunxg.file_processing.auth.configuration.security.AuthenticatedUser;
import com.vandunxg.file_processing.fileimport.application.command.UploadFileCommand;
import com.vandunxg.file_processing.fileimport.application.port.in.UploadFileUseCase;
import com.vandunxg.file_processing.fileimport.application.result.UploadFileResult;
import com.vandunxg.file_processing.fileimport.domain.exception.FileImportErrorCode;
import com.vandunxg.file_processing.fileimport.domain.exception.FileImportException;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("${app.api.prefix}/${app.api.version}/file-import/")
@RequiredArgsConstructor
@Tag(name = "File import", description = "Bearer access token required.")
public class FileImportController {

  private final UploadFileUseCase uploadFileUseCase;

  @PostMapping(
    consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
    produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Response<UploadFileResult> upload(
    @RequestPart("file") MultipartFile file,
    @AuthenticationPrincipal AuthenticatedUser principal) {
    if (file.isEmpty()) {
      throw new FileImportException(FileImportErrorCode.EMPTY_FILE);
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
}
