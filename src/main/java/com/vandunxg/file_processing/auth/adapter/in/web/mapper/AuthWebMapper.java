package com.vandunxg.file_processing.auth.adapter.in.web.mapper;

import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.LoginRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.RefreshTokenRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.RegisterRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.ResendVerificationRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.VerifyEmailRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.response.LoginResponse;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.response.MeResponse;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.response.RegisterResponse;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.response.SessionResponse;
import com.vandunxg.file_processing.auth.application.command.LoginCommand;
import com.vandunxg.file_processing.auth.application.command.RefreshTokenCommand;
import com.vandunxg.file_processing.auth.application.command.RegisterCommand;
import com.vandunxg.file_processing.auth.application.command.ResendVerificationEmailCommand;
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
  @Mapping(target = "userAgent", source = "userAgent")
  LoginCommand toCommand(LoginRequest request, String ipAddress, String userAgent);

  @Mapping(target = "ipAddress", source = "ipAddress")
  @Mapping(target = "userAgent", source = "userAgent")
  RefreshTokenCommand toCommand(RefreshTokenRequest request, String ipAddress, String userAgent);

  RegisterResponse toResponse(RegisterResult result);

  LoginResponse toResponse(LoginResult result);

  SessionResponse toResponse(SessionResult result);

  @Mapping(
      target = "status",
      expression = "java(result.getStatus() != null ? result.getStatus().name() : null)")
  MeResponse toResponse(MeResult result);
}
