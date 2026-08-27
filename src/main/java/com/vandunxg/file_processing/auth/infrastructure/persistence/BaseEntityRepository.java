package com.vandunxg.file_processing.auth.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NullMarked;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;

@NullMarked
@NoRepositoryBean
public interface BaseEntityRepository<E, U> extends JpaRepository<E, U> {
  @Query("from #{#entityName} e where e.deletedAt is not null and e.id in :ids")
  List<E> findByIds(List<U> ids);

  @Query("from #{#entityName} e where e.deletedAt is not null and e.id = :id")
  Optional<E> findById(U id);
}
