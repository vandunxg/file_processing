package com.vandunxg.file_processing.auth.adapter.out.persistence;

import org.jspecify.annotations.NullMarked;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.List;
import java.util.Optional;

@NullMarked
@NoRepositoryBean
public interface BaseEntityRepository<E, U> extends JpaRepository<E, U> {
  @Query("from #{#entityName} e where e.deletedAt is not null and e.id in :ids")
  List<E> findByIds(List<U> ids);

  @Query("from #{#entityName} e where e.deletedAt is not null and e.id = :id")
  Optional<E> findById(U id);
}
