package woozlabs.echo.domain.team.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import woozlabs.echo.domain.member.entity.Account;
import woozlabs.echo.domain.signature.Signature;
import woozlabs.echo.global.common.entity.BaseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Team extends BaseEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    private Account creator;

    @OneToMany(mappedBy = "team", cascade = CascadeType.ALL)
    private List<TeamAccount> teamAccounts = new ArrayList<>();

    @OneToMany(mappedBy = "ownerId", cascade = CascadeType.ALL)
    private List<Signature> allSignatures = new ArrayList<>();

    public List<Signature> getSignatureType() {
        return allSignatures.stream()
                .filter(signature -> signature.getType() == Signature.SignatureType.TEAM)
                .collect(Collectors.toList());
    }

    @Builder
    public Team(String name, Account creator) {
        this.name = name;
        this.creator = creator;
    }

    public void addTeamMember(TeamAccount teamAccount) {
        this.teamAccounts.add(teamAccount);
        teamAccount.setTeam(this);
    }
}
