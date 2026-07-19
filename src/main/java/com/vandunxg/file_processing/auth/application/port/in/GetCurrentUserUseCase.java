package com.vandunxg.file_processing.auth.application.port.in;

import com.vandunxg.file_processing.auth.application.query.GetCurrentUserQuery;
import com.vandunxg.file_processing.auth.application.result.MeResult;

public interface GetCurrentUserUseCase {

  MeResult me(GetCurrentUserQuery query);
}
