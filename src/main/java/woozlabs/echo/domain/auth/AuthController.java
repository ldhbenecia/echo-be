package woozlabs.echo.domain.auth;

import com.google.firebase.auth.FirebaseAuthException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import woozlabs.echo.global.aop.annotations.VerifyToken;
import woozlabs.echo.global.constant.GlobalConstant;

import java.io.IOException;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @GetMapping("/google/callback")
    public void handleOAuthCallback(@RequestParam(required = false) String code,
                                    @RequestParam(required = false) String error,
                                    @RequestParam(required = false) String state,
                                    HttpServletRequest request,
                                    HttpServletResponse response) throws FirebaseAuthException, IOException {

        if ("access_denied".equals(error)) {
            String redirectUrl = GlobalConstant.ACCESS_DENIED_GOOGLE_REDIRECT_URL;

            if (state != null) {
                redirectUrl += "?state=" + state;
            }

            response.sendRedirect(redirectUrl);
            return;
        }

        authService.handleGoogleCallback(code, request, response);
    }

    @GetMapping("/verify-token")
    @VerifyToken
    public ResponseEntity<String> testVerify() {
        return ResponseEntity.ok("Token is valid");
    }
}
