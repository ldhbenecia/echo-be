package woozlabs.echo.domain.gmail.dto.draft;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.List;

@Data
public class GmailDraftCommonRequest {
    private List<String> toEmailAddresses;
    private String fromEmailAddress;
    private String subject;
    private String bodyText;
    private List<File> files;
}