package com.vandunxg.file_processing.auth.adapter.in.web.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.RegisterRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.ResendVerificationRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.VerifyEmailRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.response.RegisterResponse;
import com.vandunxg.file_processing.auth.application.command.RegisterCommand;
import com.vandunxg.file_processing.auth.application.command.ResendVerificationEmailCommand;
import com.vandunxg.file_processing.auth.application.command.VerifyEmailCommand;
import com.vandunxg.file_processing.auth.application.result.RegisterResult;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AuthWebMapper {

  @Mapping(target = "ipAddress", source = "ipAddress")
  RegisterCommand toCommand(RegisterRequest request, String ipAddress);

  VerifyEmailCommand toCommand(VerifyEmailRequest request);

  @Mapping(target = "ipAddress", source = "ipAddress")
  ResendVerificationEmailCommand toCommand(ResendVerificationRequest request, String ipAddress);

  RegisterResponse toResponse(RegisterResult result);
}
