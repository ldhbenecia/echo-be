package woozlabs.echo.domain.gmail.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import woozlabs.echo.domain.gmail.entity.PubSubHistory;
import woozlabs.echo.domain.member.entity.Member;

import java.util.Optional;

public interface PubSubHistoryRepository extends JpaRepository<PubSubHistory, Long> {
    Optional<PubSubHistory> findByMember(Member member);
}