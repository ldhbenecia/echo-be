package woozlabs.echo.domain.echo.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import woozlabs.echo.domain.echo.entity.UserSidebarConfig;
import woozlabs.echo.domain.member.entity.Account;

import java.util.Optional;

public interface UserSideBarConfigRepository extends JpaRepository<UserSidebarConfig, Long> {

    Optional<UserSidebarConfig> findByAccount(Account account);
}
