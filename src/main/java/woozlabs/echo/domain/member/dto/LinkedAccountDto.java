package woozlabs.echo.domain.member.dto;

import lombok.*;
import woozlabs.echo.domain.member.entity.Account;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LinkedAccountDto {

    private String uid;
    private String displayName;
    private String email;
    private String profileImageUrl;
    private boolean isPrimary;

    public LinkedAccountDto(Account account) {
        this.uid = account.getUid();
        this.displayName = account.getDisplayName();
        this.email = account.getEmail();
        this.profileImageUrl = account.getProfileImageUrl();
        this.isPrimary = account.isPrimary();
    }
}
