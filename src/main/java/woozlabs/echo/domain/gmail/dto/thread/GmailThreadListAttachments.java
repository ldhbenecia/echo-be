package woozlabs.echo.domain.gmail.dto.thread;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jetbrains.annotations.NotNull;

@Data
@Builder
@EqualsAndHashCode(of = "contentId")
public class GmailThreadListAttachments{
    private String contentId;
    private String mimeType;
    private String fileName;
    private String attachmentId;
    private int size;
}