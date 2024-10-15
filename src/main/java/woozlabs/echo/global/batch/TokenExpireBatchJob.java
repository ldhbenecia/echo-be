package woozlabs.echo.global.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;
import woozlabs.echo.domain.member.entity.Account;
import woozlabs.echo.domain.member.repository.AccountRepository;

import java.time.LocalDateTime;
import java.util.Collections;

@Slf4j
@Configuration
@EnableBatchProcessing
@RequiredArgsConstructor
public class TokenExpireBatchJob {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final AccountRepository accountRepository;

    private static final int CHUNK_SIZE = 10;

    @Bean
    public Job expireTokenJob() {
        return new JobBuilder("expireTokenJob", jobRepository)
                .start(expireTokenStep())
                .build();
    }

    @Bean
    public Step expireTokenStep() {
        return new StepBuilder("expireTokenStep", jobRepository)
                .<Account, Account>chunk(CHUNK_SIZE, transactionManager)
                .reader(expireTokenReader())
                .processor(tokenExpireProcessor())
                .writer(expireTokenWriter())
                .build();
    }

    @Bean
    @StepScope
    public RepositoryItemReader<Account> expireTokenReader() {
        LocalDateTime cutoffTime = LocalDateTime.now().minusDays(10);
        log.info("TokenExpireBatchJob: Cutoff time for findByLastLoginAtBefore query: {}", cutoffTime);

        return new RepositoryItemReaderBuilder<Account>()
                .name("expireTokenReader")
                .repository(accountRepository)
                .methodName("findByLastLoginAtBefore")
                .arguments(cutoffTime)
                .pageSize(CHUNK_SIZE)
                .sorts(Collections.singletonMap("id", Sort.Direction.ASC))
                .build();
    }

    @Bean
    public ItemProcessor<Account, Account> tokenExpireProcessor() {
        return account -> {
            log.info("TokenExpireBatchJob: Expiring token for account ID: {}", account.getId());
            account.setAccessToken(null);
            account.setAccessTokenFetchedAt(null);
            return account;
        };
    }

    @Bean
    public ItemWriter<Account> expireTokenWriter() {
        return accounts -> {
            log.info("TokenExpireBatchJob: Saving {} expired accounts", accounts.size());
            accountRepository.saveAll(accounts);
        };
    }
}
