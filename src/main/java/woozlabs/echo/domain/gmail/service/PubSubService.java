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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import woozlabs.echo.domain.gmail.dto.message.GmailMessageGetResponse;
import woozlabs.echo.domain.gmail.dto.pubsub.*;
import woozlabs.echo.domain.gmail.entity.PubSubHistory;
import woozlabs.echo.domain.gmail.repository.PubSubHistoryRepository;
import woozlabs.echo.domain.gmail.validator.PubSubValidator;
import woozlabs.echo.domain.gmail.entity.FcmToken;
import woozlabs.echo.domain.member.entity.Member;
import woozlabs.echo.domain.gmail.repository.FcmTokenRepository;
import woozlabs.echo.domain.member.repository.MemberRepository;
import woozlabs.echo.global.exception.CustomErrorException;
import woozlabs.echo.global.exception.ErrorCode;

import java.io.IOException;
import java.math.BigInteger;
import java.util.*;

import static woozlabs.echo.global.constant.GlobalConstant.*;

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
    //private final List<String> PUB_SUB_HISTORY_TYPE = List.of("messageAdded");
    // injection & init
    private final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private final ObjectMapper om;
    private final MemberRepository memberRepository;
    private final FcmTokenRepository fcmTokenRepository;
    private final PubSubHistoryRepository pubSubHistoryRepository;
    private final PubSubValidator pubSubValidator;
    private final GmailService gmailServiceImpl;

    @Transactional
    public void handleFirebaseCloudMessage(PubSubMessage pubsubMessage) throws Exception {
        String messageData = pubsubMessage.getMessage().getData();
        String decodedData = new String(java.util.Base64.getDecoder().decode(messageData));
        PubSubNotification notification = om.readValue(decodedData, PubSubNotification.class);
        String email = notification.getEmailAddress();
        BigInteger newHistoryId = new BigInteger(notification.getHistoryId());
        Member member = memberRepository.findByEmail(email).orElseThrow(
                () -> new CustomErrorException(ErrorCode.NOT_FOUND_MEMBER_ERROR_MESSAGE)
        );
        PubSubHistory pubSubHistory = pubSubHistoryRepository.findByMember(member).orElseThrow(
                () -> new CustomErrorException(ErrorCode.NOT_FOUND_PUB_SUB_HISTORY_ERR)
        );
        Gmail gmailService = createGmailService(member.getAccessToken());
        List<String> fcmTokens = fcmTokenRepository.findByMember(member).stream().map(FcmToken::getFcmToken).toList();
        List<MessageInHistoryData> getHistoryList = getHistoryListById(pubSubHistory, newHistoryId, gmailService);
        if(getHistoryList.isEmpty()) return; // watch message
        // send multicast messages
        getHistoryList.forEach((historyData) -> {
            try{
                // get detailed message info
                GmailMessageGetResponse gmailMessage = gmailServiceImpl.getUserEmailMessage(member.getUid(), historyData.getId());
                String subject = gmailMessage.getSubject();
                String snippet = gmailMessage.getSnippet();
                Map<String, String> data = new HashMap<>();
                createMessageData(historyData, data, gmailMessage);
                // create firebase message
                MulticastMessage message = MulticastMessage.builder()
                        .setNotification(Notification.builder()
                                .setTitle(subject)
                                .setBody(snippet)
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
        });
    }

    @Transactional
    public FcmTokenResponse saveFcmToken(String uid, FcmTokenRequest dto){
        Member member = memberRepository.findByUid(uid).orElseThrow(
                () -> new CustomErrorException(ErrorCode.NOT_FOUND_MEMBER_ERROR_MESSAGE));
        List<FcmToken> tokens = fcmTokenRepository.findByMember(member);
        pubSubValidator.validateSaveFcmToken(tokens, dto.getFcmToken());
        FcmToken newFcmToken = FcmToken.builder()
                .fcmToken(dto.getFcmToken())
                .machineUuid(dto.getMachineUuid())
                .member(member).build();
        fcmTokenRepository.save(newFcmToken);
        return new FcmTokenResponse(newFcmToken.getId());
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
                    (message) -> MessageInHistoryData.toMessageInHistoryData(message.getMessage(), HistoryType.MESSAGE_ADDED)).toList()
                    : Collections.emptyList());
            historyDataList.addAll(history.getMessagesDeleted() != null
                    ? history.getMessagesDeleted().stream().map(
                    (message) -> MessageInHistoryData.toMessageInHistoryData(message.getMessage(), HistoryType.MESSAGE_DELETED)).toList()
                    : Collections.emptyList());
            historyDataList.addAll(history.getLabelsAdded() != null
                    ? history.getLabelsAdded().stream().map(
                    (message) -> MessageInHistoryData.toMessageInHistoryData(message.getMessage(), HistoryType.LABEL_ADDED)).toList()
                    : Collections.emptyList());
            historyDataList.addAll(history.getLabelsRemoved() != null
                    ? history.getLabelsRemoved().stream().map(
                    (message) -> MessageInHistoryData.toMessageInHistoryData(message.getMessage(), HistoryType.LABEL_REMOVED)).toList()
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

    private void createMessageData(MessageInHistoryData historyData, Map<String, String> data, GmailMessageGetResponse gmailMessage) {
        String FCM_MSG_ID_KEY = "id";
        String FCM_MSG_THREAD_ID_KEY = "threadId";
        String FCM_MSG_TYPE_KEY = "type";
        String FCM_MSG_VERIFICATION_KEY = "verification";
        String FCM_MSG_CODE_KEY = "code";
        String FCM_MSG_LINK_KEY = "link";
        // set base info
        data.put(FCM_MSG_ID_KEY, historyData.getId());
        data.put(FCM_MSG_THREAD_ID_KEY, historyData.getThreadId());
        data.put(FCM_MSG_TYPE_KEY, historyData.getHistoryType().getType());
        // set verification data
        List<String> codes = gmailMessage.getVerification().getCodes();
        List<String> links = gmailMessage.getVerification().getLinks();
        data.put(FCM_MSG_VERIFICATION_KEY, gmailMessage.getVerification().getVerification().toString());
        data.put(FCM_MSG_CODE_KEY, String.join(",", codes));
        data.put(FCM_MSG_LINK_KEY, String.join(",", links));
        // set schedule data
    }
}