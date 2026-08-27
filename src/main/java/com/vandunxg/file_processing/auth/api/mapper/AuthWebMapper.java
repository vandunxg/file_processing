package com.vandunxg.file_processing.auth.api.mapper;

import java.util.UUID;

import com.vandunxg.file_processing.auth.api.dto.request.ChangePasswordRequest;
import com.vandunxg.file_processing.auth.api.dto.request.ForgotPasswordRequest;
import com.vandunxg.file_processing.auth.api.dto.request.LoginRequest;
import com.vandunxg.file_processing.auth.api.dto.request.RegisterRequest;
import com.vandunxg.file_processing.auth.api.dto.request.ResendVerificationRequest;
import com.vandunxg.file_processing.auth.api.dto.request.ResetPasswordRequest;
import com.vandunxg.file_processing.auth.api.dto.request.VerifyEmailRequest;
import com.vandunxg.file_processing.auth.api.dto.response.LoginResponse;
import com.vandunxg.file_processing.auth.api.dto.response.MeResponse;
import com.vandunxg.file_processing.auth.api.dto.response.RegisterResponse;
import com.vandunxg.file_processing.auth.api.dto.response.SessionResponse;
import com.vandunxg.file_processing.auth.application.command.ChangePasswordCommand;
import com.vandunxg.file_processing.auth.application.command.ForgotPasswordCommand;
import com.vandunxg.file_processing.auth.application.command.LoginCommand;
import com.vandunxg.file_processing.auth.application.command.RegisterCommand;
import com.vandunxg.file_processing.auth.application.command.ResendVerificationEmailCommand;
import com.vandunxg.file_processing.auth.application.command.ResetPasswordCommand;
import com.vandunxg.file_processing.auth.application.command.VerifyEmailCommand;
import com.vandunxg.file_processing.auth.application.result.LoginResult;
import com.vandunxg.file_processing.auth.application.result.MeResult;
import com.vandunxg.file_processing.auth.application.result.RegisterResult;
import com.vandunxg.file_processing.auth.application.result.SessionResult;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AuthWebMapper {

  @Mapping(target = "ipAddress", source = "ipAddress")
  RegisterCommand toCommand(RegisterRequest request, String ipAddress);

  VerifyEmailCommand toCommand(VerifyEmailRequest request);

  @Mapping(target = "ipAddress", source = "ipAddress")
  ResendVerificationEmailCommand toCommand(ResendVerificationRequest request, String ipAddress);

  @Mapping(target = "ipAddress", source = "ipAddress")
  ForgotPasswordCommand toCommand(ForgotPasswordRequest request, String ipAddress);

  @Mapping(target = "ipAddress", source = "ipAddress")
  ResetPasswordCommand toCommand(ResetPasswordRequest request, String ipAddress);

  @Mapping(target = "userId", source = "userId")
  @Mapping(target = "ipAddress", source = "ipAddress")
  ChangePasswordCommand toCommand(ChangePasswordRequest request, UUID userId, String ipAddress);

  @Mapping(target = "ipAddress", source = "ipAddress")
  @Mapping(target = "userAgent", source = "userAgent")
  LoginCommand toCommand(LoginRequest request, String ipAddress, String userAgent);

  RegisterResponse toResponse(RegisterResult result);

  LoginResponse toResponse(LoginResult result);

  SessionResponse toResponse(SessionResult result);

  @Mapping(
      target = "status",
      expression = "java(result.getStatus() != null ? result.getStatus().name() : null)")
  MeResponse toResponse(MeResult result);
}
