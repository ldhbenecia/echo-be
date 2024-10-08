package woozlabs.echo.domain.gmail.dto.draft;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class GmailDraftListDrafts {
    private String id;
    private List<String> labelIds;
    private String mimeType;
    private int attachmentSize;
    private List<GmailDraftListAttachments> attachments;
    private List<String> fromName;
    private List<String> fromEmail;
    private String subject;
    private String snippet;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Seoul")
    private LocalDateTime internalDate;
}
