package com.microlearning.todo.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * UserRepository — data access layer for the User domain.
 *
 * ARCHITECTURE NOTE:
 * In the monolith, this repository is called directly by UserService
 * (in-process, zero network overhead).
 *
 * In microservices, this repository would live inside the User Service.
 * Any other service that needs user data would call the User Service's
 * REST API — it would NEVER inject this repository directly.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
