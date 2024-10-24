package woozlabs.echo.domain.gmail.dto.template;

import lombok.Data;
import woozlabs.echo.global.dto.ResponseDto;

import java.util.List;

@Data
public class ExtractScheduleInfo implements ResponseDto {
    private List<String> dt;
    private List<String> loc;
    private List<String> per;
    private Boolean isSchedule;
}