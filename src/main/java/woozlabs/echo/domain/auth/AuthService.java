package woozlabs.echo.domain.auth;

import com.google.firebase.auth.FirebaseAuthException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;
import woozlabs.echo.domain.auth.utils.AuthCookieUtils;
import woozlabs.echo.domain.auth.utils.AuthUtils;
import woozlabs.echo.domain.auth.utils.FirebaseUtils;
import woozlabs.echo.domain.member.entity.Account;
import woozlabs.echo.domain.member.entity.Member;
import woozlabs.echo.domain.member.entity.MemberAccount;
import woozlabs.echo.domain.member.entity.Watch;
import woozlabs.echo.domain.member.repository.AccountRepository;
import woozlabs.echo.domain.member.repository.MemberAccountRepository;
import woozlabs.echo.domain.member.repository.MemberRepository;
import woozlabs.echo.global.constant.GlobalConstant;
import woozlabs.echo.global.exception.CustomErrorException;
import woozlabs.echo.global.exception.ErrorCode;
import woozlabs.echo.global.utils.FirebaseTokenVerifier;
import woozlabs.echo.global.utils.GoogleOAuthUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AuthService {

    private final AccountRepository accountRepository;
    private final MemberRepository memberRepository;
    private final MemberAccountRepository memberAccountRepository;
    private final FirebaseTokenVerifier firebaseTokenVerifier;
    private final FirebaseUtils firebaseUtils;
    private final GoogleOAuthUtils googleOAuthUtils;

    private static final String GOOGLE_PROVIDER = "google";

    private void constructAndRedirect(HttpServletResponse response, String customToken, String displayName, String profileImageUrl, String email, boolean isAddAccount) {
        String baseUrl = isAddAccount ? GlobalConstant.AUTH_ADD_ACCOUNT_DOMAIN : GlobalConstant.AUTH_SIGN_IN_DOMAIN;
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .queryParam("customToken", customToken)
                .queryParam("displayName", displayName)
                .queryParam("profileImageUrl", profileImageUrl)
                .queryParam("email", email)
                .toUriString();

        try {
            response.sendRedirect(url);
        } catch (Exception e) {
            throw new CustomErrorException(ErrorCode.FAILED_TO_REDIRECT_GOOGLE_USER_INFO);
        }
    }

    private Account createNewAccount(String providerId, String displayName, String email, String profileImageUrl, String accessToken, String refreshToken, String uuid, String provider) {
        log.info("Creating new account with providerId: {}, email: {}", providerId, email);
        return Account.builder()
                .uid(uuid)
                .providerId(providerId)
                .displayName(displayName)
                .email(email)
                .profileImageUrl(profileImageUrl)
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .accessTokenFetchedAt(LocalDateTime.now())
                .provider(provider)
                .build();
    }

    private void updateAccountInfo(Account account, Map<String, Object> userInfo) {
        account.setDisplayName((String) userInfo.get("name"));
        account.setEmail((String) userInfo.get("email"));
        account.setProfileImageUrl((String) userInfo.get("picture"));
        account.setAccessToken((String) userInfo.get("access_token"));
        String refreshToken = (String) userInfo.get("refresh_token");
        if (refreshToken != null) {
            account.setRefreshToken(refreshToken);
        }
        account.setAccessTokenFetchedAt(LocalDateTime.now());
        account.setProvider(GOOGLE_PROVIDER);
    }

    @Transactional
    public void handleGoogleCallback(String code, HttpServletRequest request, HttpServletResponse response) throws FirebaseAuthException {
        Map<String, Object> userInfo = googleOAuthUtils.getGoogleUserInfoAndTokens(code);
        String providerId = (String) userInfo.get("id");

        Optional<Account> existingAccountOpt = accountRepository.findByProviderId(providerId);

        if (existingAccountOpt.isPresent()) {
            log.info("Account with providerId: {} found. Handling existing account.", providerId);
            handleExistingAccount(existingAccountOpt.get(), userInfo, request, response);
        } else {
            log.info("No account found with providerId: {}. Handling new account.", providerId);
            handleNewAccount(userInfo, request, response);
        }
    }

    @Transactional
    public Account createOrUpdateAccount(Map<String, Object> userInfo) throws FirebaseAuthException {
        String providerId = (String) userInfo.get("id");
        String displayName = (String) userInfo.get("name");
        String email = (String) userInfo.get("email");
        String profileImageUrl = (String) userInfo.get("picture");
        String accessToken = (String) userInfo.get("access_token");
        String refreshToken = (String) userInfo.get("refresh_token");

        boolean emailExists = firebaseUtils.checkIfEmailExists(email);
        String uuid;

        if (emailExists) {
            Account existingAccount = accountRepository.findByEmail(email)
                    .orElseThrow(() -> new CustomErrorException(ErrorCode.NOT_FOUND_ACCOUNT_ERROR_MESSAGE));
            uuid = existingAccount.getUid();
            updateAccountInfo(existingAccount, userInfo);
            accountRepository.save(existingAccount);
            log.info("Updated existing account with UID: {}", uuid);
            return existingAccount;
        } else {
            uuid = UUID.nameUUIDFromBytes(email.getBytes(StandardCharsets.UTF_8)).toString();
            Account newAccount = createNewAccount(providerId, displayName, email, profileImageUrl, accessToken, refreshToken, uuid, GOOGLE_PROVIDER);
            accountRepository.save(newAccount);
            log.info("Created new account with UID: {}", newAccount.getUid());
            return newAccount;
        }
    }

    @Transactional
    public void handleExistingAccount(Account existingAccount, Map<String, Object> userInfo, HttpServletRequest request, HttpServletResponse response) throws FirebaseAuthException {
        updateAccountInfo(existingAccount, userInfo);

        Optional<String> cookieTokenOpt = AuthCookieUtils.getCookieValue(request);

        if (cookieTokenOpt.isPresent()) {
            log.info("Token found in cookie. Verifying token.");
            String uid = firebaseTokenVerifier.verifyTokenAndGetUid(cookieTokenOpt.get());
            Member cookieTokenMember = memberRepository.findByPrimaryUid(uid)
                    .orElseThrow(() -> new CustomErrorException(ErrorCode.NOT_FOUND_MEMBER));

            addNewAccountToExistingMember(cookieTokenMember, userInfo, response);
        } else {
            log.info("No token found in cookie. Updating existing account and redirecting.");
            accountRepository.save(existingAccount);
            constructAndRedirect(response, firebaseUtils.createCustomToken(existingAccount.getUid()), (String) userInfo.get("name"), (String) userInfo.get("picture"), (String) userInfo.get("email"), false);
        }
    }

    public void handleNewAccount(Map<String, Object> userInfo, HttpServletRequest request, HttpServletResponse response) throws FirebaseAuthException {
        log.info("Handling new account for user: {}", userInfo.get("email"));
        Optional<String> cookieTokenOpt = AuthCookieUtils.getCookieValue(request);

        if (cookieTokenOpt.isPresent()) {
            String tokenValue = cookieTokenOpt.get();
            String uid = firebaseTokenVerifier.verifyTokenAndGetUid(tokenValue);
            Member cookieTokenMember = memberRepository.findByPrimaryUid(uid)
                    .orElse(null);

            if (cookieTokenMember != null) {
                log.info("Member found, adding new account to existing member.");
                addNewAccountToExistingMember(cookieTokenMember, userInfo, response);
            } else {
                log.warn("Unexpected case: Member is null after lookup.");
                createNewMemberWithAccount(userInfo, response);
            }
        } else {
            log.info("No token found in cookie, creating new member with account.");
            createNewMemberWithAccount(userInfo, response);
        }
    }

    @Transactional
    public void addNewAccountToExistingMember(Member member, Map<String, Object> userInfo, HttpServletResponse response) throws FirebaseAuthException {
        log.info("Adding new account to existing member.");
        Account newAccount = createOrUpdateAccount(userInfo);

        boolean accountExists = member.getMemberAccounts().stream()
                .anyMatch(ma -> ma.getAccount().equals(newAccount));

        if (!accountExists) {
            MemberAccount memberAccount = new MemberAccount(member, newAccount);
            member.addMemberAccount(memberAccount);
            member.setDeletedAt(null);

            memberAccountRepository.save(memberAccount);
            log.info("Added new account to existing member. Account UID: {}", newAccount.getUid());
        } else {
            log.info("Account already associated with this member. UID: {}", newAccount.getUid());
        }

        memberRepository.save(member);
        accountRepository.save(newAccount);

        constructAndRedirect(response, firebaseUtils.createCustomToken(newAccount.getUid()), (String) userInfo.get("name"), (String) userInfo.get("picture"), (String) userInfo.get("email"), true);
    }

    @Transactional
    public void createNewMemberWithAccount(Map<String, Object> userInfo, HttpServletResponse response) throws FirebaseAuthException {
        log.info("Creating new member with new account for user: {}", userInfo.get("email"));
        Account account = createOrUpdateAccount(userInfo);

        String displayName = (String) userInfo.get("name");
        String memberName = displayName + "-" + AuthUtils.generateRandomString();
        String email = (String) userInfo.get("email");

        Member member = new Member();
        member.setPrimaryUid(account.getUid());
        member.setMemberName(memberName);
        member.setDisplayName(displayName);
        member.setEmail(email);
        member.setProfileImageUrl((String) userInfo.get("picture"));
        member.setWatchNotifications(Map.of(account.getUid(), Watch.INBOX));

        MemberAccount memberAccount = new MemberAccount(member, account);
        member.addMemberAccount(memberAccount);
        account.getMemberAccounts().add(memberAccount);

        member.setDeletedAt(null);

        memberRepository.save(member);
        accountRepository.save(account);

        log.info("Created new Member with a new account. Account UID: {}", account.getUid());

        String customToken = firebaseUtils.createCustomToken(account.getUid());
        constructAndRedirect(response, customToken, (String) userInfo.get("name"), (String) userInfo.get("picture"), (String) userInfo.get("email"), false);
    }
}
