package woozlabs.echo.global.scheduler;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import woozlabs.echo.domain.member.repository.AccountRepository;
import woozlabs.echo.domain.member.repository.MemberAccountRepository;
import woozlabs.echo.domain.member.repository.MemberRepository;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiredMemberCleanupScheduler {

    private final MemberRepository memberRepository;
    private final MemberAccountRepository memberAccountRepository;
    private final AccountRepository accountRepository;

    @Scheduled(cron = "0 0 0 * * ?")
    @Transactional
    public void hardDeleteExpiredMembers() {
        LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);

        // 1. Find expired members
        List<Long> expiredMemberIds = memberRepository.findExpiredMemberIds(thirtyDaysAgo);

        if (expiredMemberIds.isEmpty()) {
            log.info("No expired members found");
            return;
        }

        // 2. Find accounts associated only with expired members
        List<String> accountUidsToDelete = accountRepository.findUidsAssociatedOnlyWithExpiredMembers(expiredMemberIds);

        // 3. Bulk delete MemberAccounts for expired members
        int deletedMemberAccounts = memberAccountRepository.bulkDeleteByMemberIds(expiredMemberIds);
        log.info("Deleted {} MemberAccounts for expired members", deletedMemberAccounts);

        // 4. Bulk delete expired Members
        int deletedMembers = memberRepository.bulkDeleteByIds(expiredMemberIds);
        log.info("Hard deleted {} expired members", deletedMembers);

        // 5. Bulk delete Accounts associated only with expired members
        int deletedAccounts = accountRepository.bulkDeleteByUids(accountUidsToDelete);
        log.info("Deleted {} associated accounts", deletedAccounts);

        // 6. Delete Firebase accounts
        FirebaseAuth firebaseAuth = FirebaseAuth.getInstance();
        for (String uid : accountUidsToDelete) {
            try {
                firebaseAuth.deleteUser(uid);
                log.info("Deleted Firebase account: {}", uid);
            } catch (FirebaseAuthException e) {
                log.error("Failed to delete Firebase account: " + uid, e);
            }
        }
    }
}
