package woozlabs.echo.domain.contact.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import woozlabs.echo.domain.contact.dto.GoogleContactResponseDto;
import woozlabs.echo.domain.contact.service.GooglePeopleService;

import java.io.IOException;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/echo/contacts")
public class GooglePeopleController {

    private final GooglePeopleService googlePeopleService;

    @GetMapping("/other")
    public ResponseEntity<List<GoogleContactResponseDto>> getOtherContacts() throws IOException {
        List<GoogleContactResponseDto> response = googlePeopleService.getOtherContacts();
        return ResponseEntity.ok(response);
    }
}
