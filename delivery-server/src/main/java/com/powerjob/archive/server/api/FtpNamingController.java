package com.powerjob.archive.server.api;

import com.powerjob.archive.core.service.FtpNamingService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 仅回环管理入口：收录实物重命名的真实回验收据，不能直接改交付成功状态。 */
@RestController
@RequestMapping("/api/v1/delivery-entities/{vendorCode}/{entityCode}/ftp-names")
public class FtpNamingController {
    private final FtpNamingService naming;
    public FtpNamingController(FtpNamingService naming) { this.naming=naming; }
    @PostMapping("/reconcile")
    public Map<String,Object> reconcile(@PathVariable String vendorCode,@PathVariable String entityCode,@RequestBody FtpNamingService.Command command) {
        return naming.adopt(vendorCode,entityCode,command);
    }
}
