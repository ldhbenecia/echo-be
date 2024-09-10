package woozlabs.echo.domain.sharedEmail.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import woozlabs.echo.domain.sharedEmail.dto.GetSharedEmailResponseDto;
import woozlabs.echo.domain.sharedEmail.dto.ShareEmailRequestDto;
import woozlabs.echo.domain.sharedEmail.dto.thread.ThreadGetResponse;
import woozlabs.echo.domain.sharedEmail.service.SharedInboxService;
import woozlabs.echo.global.constant.GlobalConstant;

@RestController
@RequestMapping("/api/v1/echo")
@RequiredArgsConstructor
public class SharedInboxController {

    private final SharedInboxService sharedInboxService;

    @PostMapping("/public-share")
    public ResponseEntity<ThreadGetResponse> shareEmail(HttpServletRequest httpServletRequest,
                                                        @RequestBody ShareEmailRequestDto shareEmailRequestDto) {
        String uid = (String) httpServletRequest.getAttribute(GlobalConstant.FIREBASE_UID_KEY);
        sharedInboxService.publicShareEmail(uid, shareEmailRequestDto);
        return ResponseEntity.status(201).build();
    }

    @GetMapping("/public-share/{dataId}")
    public ResponseEntity<GetSharedEmailResponseDto> getSharedEmail(HttpServletRequest httpServletRequest,
                                                                    @PathVariable("dataId") String dataId) {
        String uid = (String) httpServletRequest.getAttribute(GlobalConstant.FIREBASE_UID_KEY);
        GetSharedEmailResponseDto responseDto = sharedInboxService.getSharedEmail(uid, dataId);
        return ResponseEntity.ok(responseDto);
    }
}
