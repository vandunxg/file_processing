package com.vandunxg.file_processing.auth.application.port.in;

import java.util.List;

import com.vandunxg.file_processing.auth.application.query.ListSessionsQuery;
import com.vandunxg.file_processing.auth.application.result.SessionResult;

public interface ListSessionsUseCase {

  List<SessionResult> list(ListSessionsQuery query);
}
