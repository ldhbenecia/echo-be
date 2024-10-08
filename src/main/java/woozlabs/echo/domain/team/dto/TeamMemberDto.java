package woozlabs.echo.domain.team.dto;

import lombok.Builder;
import lombok.Getter;
import woozlabs.echo.domain.team.entity.Role;

@Getter
@Builder
public class TeamMemberDto {
    private Long id;
    private String memberUid;
    private Role role;
}
