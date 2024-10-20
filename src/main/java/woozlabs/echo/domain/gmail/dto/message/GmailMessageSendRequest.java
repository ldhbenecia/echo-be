package woozlabs.echo.domain.gmail.dto.message;

import lombok.Data;

import java.io.File;
import java.util.List;

@Data
public class GmailMessageSendRequest {
    private List<String> toEmailAddresses;
    private String fromEmailAddress;
    private String subject;
    private String bodyText;
    private List<File> files;
}