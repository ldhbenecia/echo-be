package woozlabs.echo.domain.contactGroup.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import woozlabs.echo.domain.contactGroup.entity.ContactGroup;
import woozlabs.echo.domain.member.entity.Account;

import java.util.List;

public interface ContactGroupRepository extends JpaRepository<ContactGroup, Long> {

    List<ContactGroup> findByOwner(Account owner);
}
