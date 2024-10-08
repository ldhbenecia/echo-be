package woozlabs.echo.domain.gmail.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.History;
import com.google.api.services.gmail.model.ListHistoryResponse;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.messaging.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import woozlabs.echo.domain.gmail.dto.message.GmailMessageGetResponse;
import woozlabs.echo.domain.gmail.dto.pubsub.*;
import woozlabs.echo.domain.gmail.entity.PubSubHistory;
import woozlabs.echo.domain.gmail.entity.VerificationEmail;
import woozlabs.echo.domain.gmail.repository.PubSubHistoryRepository;
import woozlabs.echo.domain.gmail.repository.VerificationEmailRepository;
import woozlabs.echo.domain.gmail.validator.PubSubValidator;
import woozlabs.echo.domain.gmail.entity.FcmToken;
import woozlabs.echo.domain.member.entity.Account;
import woozlabs.echo.domain.gmail.repository.FcmTokenRepository;
import woozlabs.echo.domain.member.repository.AccountRepository;
import woozlabs.echo.global.constant.GlobalConstant;
import woozlabs.echo.global.exception.CustomErrorException;
import woozlabs.echo.global.exception.ErrorCode;

import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static woozlabs.echo.global.constant.GlobalConstant.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PubSubService {
    private final List<String> SCOPES = Arrays.asList(
            "https://www.googleapis.com/auth/gmail.readonly",
            "https://www.googleapis.com/auth/userinfo.profile",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/gmail.modify",
            "https://mail.google.com/"

    );
    private final Long MAX_HISTORY_COUNT = 50L;
    private final String PUB_SUB_LABEL_ID = "INBOX";
    private final String DOMAIN_PATTERN = "(?i)^(https?://(?:www\\.)?[^/]+)";
    // injection & init
    private final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private final ObjectMapper om;
    private final AccountRepository accountRepository;
    private final FcmTokenRepository fcmTokenRepository;
    private final PubSubHistoryRepository pubSubHistoryRepository;
    private final VerificationEmailRepository verificationEmailRepository;
    private final PubSubValidator pubSubValidator;
    private final GmailService gmailServiceImpl;

    @Transactional
    public void handleFirebaseCloudMessage(PubSubMessage pubsubMessage) throws Exception {
        String messageData = pubsubMessage.getMessage().getData();
        String decodedData = new String(java.util.Base64.getDecoder().decode(messageData));
        PubSubNotification notification = om.readValue(decodedData, PubSubNotification.class);
        String email = notification.getEmailAddress();
        int deliveryAttempt = pubsubMessage.getDeliveryAttempt();
        log.info(String.valueOf(deliveryAttempt));
        if(deliveryAttempt > 2){ // stop pub/sub alert(* case: failed to alert more than three times)
            log.info("Exceed delivery attempt limit");
            return;
        }
        BigInteger newHistoryId = new BigInteger(notification.getHistoryId());
        Account account = accountRepository.findByEmail(email).orElseThrow(
                () -> new CustomErrorException(ErrorCode.NOT_FOUND_ACCOUNT_ERROR_MESSAGE, ErrorCode.NOT_FOUND_ACCOUNT_ERROR_MESSAGE.getMessage())
        );
        PubSubHistory pubSubHistory = pubSubHistoryRepository.findByAccount(account).orElseThrow(
                () -> new CustomErrorException(ErrorCode.NOT_FOUND_PUB_SUB_HISTORY_ERR, ErrorCode.NOT_FOUND_PUB_SUB_HISTORY_ERR.getMessage())
        );
        Gmail gmailService = createGmailService(account.getAccessToken());
        List<String> fcmTokens = fcmTokenRepository.findByAccount(account).stream().map(FcmToken::getFcmToken).toList();
        List<MessageInHistoryData> getHistoryList = getHistoryListById(pubSubHistory, newHistoryId, gmailService);
        if(getHistoryList.isEmpty()) return; // watch message
        processForwardedMessage(getHistoryList);
        // send multicast messages
        for(MessageInHistoryData historyData : getHistoryList){
            try{
                // get detailed message info
                GmailMessageGetResponse gmailMessage = gmailServiceImpl.getUserEmailMessage(account.getUid(), historyData.getId());
                HistoryType historyType = historyData.getHistoryType();
                String from = historyType.equals(HistoryType.MESSAGE_DELETED) ?
                        DELETED_MESSAGE_ALERT_MSG :
                        gmailMessage.getFrom().getEmail();
                String subject = historyType.equals(HistoryType.MESSAGE_DELETED) ?
                        DELETED_MESSAGE_ALERT_MSG :
                        gmailMessage.getSubject();
                Map<String, String> data = new HashMap<>();
                createMessageData(historyData, data, gmailMessage, account);
                // create firebase message
                MulticastMessage message = MulticastMessage.builder()
                        .setNotification(Notification.builder()
                                .setTitle(from)
                                .setBody(subject)
                                .build()
                        )
                        .putAllData(data)
                        .addAllTokens(fcmTokens)
                        .build();
                FirebaseMessaging.getInstance().sendEachForMulticastAsync(message);
            }catch (FirebaseMessagingException e) {
                throw new CustomErrorException(ErrorCode.FIREBASE_CLOUD_MESSAGING_SEND_ERR, ErrorCode.FIREBASE_CLOUD_MESSAGING_SEND_ERR.getMessage());
            } catch (Exception e) {
                throw new CustomErrorException(ErrorCode.FAILED_TO_GET_GMAIL_CONNECTION_REQUEST, ErrorCode.FAILED_TO_GET_GMAIL_CONNECTION_REQUEST.getMessage());
            }
        }
    }

    private static void processForwardedMessage(List<MessageInHistoryData> getHistoryList) {
        // process forwarded email
        if(getHistoryList.size() == 3){
            HistoryType firstType = getHistoryList.get(0).getHistoryType();
            HistoryType secondType = getHistoryList.get(1).getHistoryType();
            HistoryType thirdType = getHistoryList.get(2).getHistoryType();
            if(firstType.equals(HistoryType.MESSAGE_ADDED) &&
                    secondType.equals(HistoryType.MESSAGE_DELETED) &&
                    thirdType.equals(HistoryType.MESSAGE_ADDED)){
                getHistoryList.subList(0,2).clear();
            }
        }
    }

    @Transactional
    public FcmTokenResponse saveFcmToken(String uid, FcmTokenRequest dto){
        Account account = accountRepository.findByUid(uid).orElseThrow(
                () -> new CustomErrorException(ErrorCode.NOT_FOUND_ACCOUNT_ERROR_MESSAGE));
        Optional<FcmToken> findFcmToken = fcmTokenRepository.findByAccountAndMachineUuid(account, dto.getMachineUuid());
        if(findFcmToken.isEmpty()){
            List<FcmToken> tokens = fcmTokenRepository.findByAccount(account);
            pubSubValidator.validateSaveFcmToken(tokens, dto.getFcmToken());
            FcmToken newFcmToken = FcmToken.builder()
                    .fcmToken(dto.getFcmToken())
                    .machineUuid(dto.getMachineUuid())
                    .account(account).build();
            fcmTokenRepository.save(newFcmToken);
            return new FcmTokenResponse(newFcmToken.getId());
        }
        FcmToken existFcmToken = findFcmToken.get();
        existFcmToken.updateFcmToken(dto.getFcmToken());
        return new FcmTokenResponse(existFcmToken.getId());
    }

    private List<MessageInHistoryData> getHistoryListById(PubSubHistory pubSubHistory, BigInteger newHistoryId, Gmail gmailService) throws IOException {
        BigInteger historyId = pubSubHistory.getHistoryId();
        ListHistoryResponse historyResponse = gmailService.users().history()
                .list(USER_ID)
                .setStartHistoryId(historyId)
                .setLabelId(PUB_SUB_LABEL_ID)
                .setMaxResults(MAX_HISTORY_COUNT)
                .execute();
        List<History> histories = historyResponse.getHistory();
        List<MessageInHistoryData> historyDataList = new ArrayList<>();
        if(histories == null) return historyDataList;
        for(History history : histories){
            // add addedMessages
            historyDataList.addAll(history.getMessagesAdded() != null
                    ? history.getMessagesAdded().stream().map(
                    (message) -> MessageInHistoryData.toMessageInHistoryData(message.getMessage(), HistoryType.MESSAGE_ADDED, Collections.emptyList())).toList()
                    : Collections.emptyList());
            historyDataList.addAll(history.getMessagesDeleted() != null
                    ? history.getMessagesDeleted().stream().map(
                    (message) -> MessageInHistoryData.toMessageInHistoryData(message.getMessage(), HistoryType.MESSAGE_DELETED, Collections.emptyList())).toList()
                    : Collections.emptyList());
            historyDataList.addAll(history.getLabelsAdded() != null
                    ? history.getLabelsAdded().stream().map(
                    (message) -> MessageInHistoryData.toMessageInHistoryData(message.getMessage(), HistoryType.LABEL_ADDED, message.getLabelIds())).toList()
                    : Collections.emptyList());
            historyDataList.addAll(history.getLabelsRemoved() != null
                    ? history.getLabelsRemoved().stream().map(
                    (message) -> MessageInHistoryData.toMessageInHistoryData(message.getMessage(), HistoryType.LABEL_REMOVED, message.getLabelIds())).toList()
                    : Collections.emptyList());
        }
        pubSubHistory.updateHistoryId(newHistoryId);
        return historyDataList;
    }

    private Gmail createGmailService(String accessToken) throws Exception{
        HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        HttpRequestInitializer requestInitializer = createCredentialWithAccessToken(accessToken);
        return new Gmail.Builder(httpTransport, JSON_FACTORY, requestInitializer)
                .setApplicationName("Echo")
                .build();
    }

    private HttpRequestInitializer createCredentialWithAccessToken(String accessToken){
        AccessToken token = AccessToken.newBuilder()
                .setTokenValue(accessToken)
                .setScopes(SCOPES)
                .build();
        GoogleCredentials googleCredentials = GoogleCredentials.create(token);
        return new HttpCredentialsAdapter(googleCredentials);
    }

    private void createMessageData(MessageInHistoryData historyData, Map<String, String> data, GmailMessageGetResponse gmailMessage, Account owner) {
        String FCM_MSG_ID_KEY = "id";
        String FCM_MSG_THREAD_ID_KEY = "threadId";
        String FCM_MSG_TYPE_KEY = "type";
        String FCM_MSG_VERIFICATION_KEY = "verification";
        String FCM_MSG_LABEL_KEY = "label";
        HistoryType historyType = historyData.getHistoryType();
        // set base info
        data.put(FCM_MSG_ID_KEY, historyData.getId());
        data.put(FCM_MSG_THREAD_ID_KEY, historyData.getThreadId());
        data.put(FCM_MSG_TYPE_KEY, historyData.getHistoryType().getType());
        if(historyType.equals(HistoryType.MESSAGE_ADDED)){
            // set verification data
            Boolean isVerification = gmailMessage.getVerification().getVerification();
            data.put(FCM_MSG_VERIFICATION_KEY, isVerification.toString());
            if(isVerification.equals(true)){ // save verification email
                VerificationEmail verificationEmail = VerificationEmail.builder()
                        .threadId(historyData.getThreadId())
                        .messageId(historyData.getId())
                        .codes(String.join(",",gmailMessage.getVerification().getCodes()))
                        .links(String.join(",",gmailMessage.getVerification().getLinks()))
                        .account(owner)
                        .build();
                verificationEmailRepository.save(verificationEmail);
            }
        }else if(historyType.equals(HistoryType.LABEL_ADDED) || historyType.equals(HistoryType.LABEL_REMOVED)){
            List<String> labelIds = historyData.getLabelIds();
            data.put(FCM_MSG_LABEL_KEY, String.join(",", labelIds));
        }
        // set schedule data
    }
}