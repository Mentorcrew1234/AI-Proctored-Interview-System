package com.project.proctorinterview.user;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.project.proctorinterview.common.Enums.Role;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<User> findByRoleOrderByFullNameAsc(Role role);

    /** The unfiltered admin list, in the same order the role-scoped query uses. */
    List<User> findAllByOrderByFullNameAsc();

    /**
     * Count without fetching. The admin dashboard needs "how many candidates",
     * not the candidates themselves.
     */
    long countByRole(Role role);

    long countByRoleAndEnabled(Role role, boolean enabled);
}
