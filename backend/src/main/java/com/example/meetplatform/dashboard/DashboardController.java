package com.example.meetplatform.dashboard;

import com.example.meetplatform.common.security.CurrentUserProvider;
import com.example.meetplatform.dashboard.DashboardService.DashboardSummary;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Dashboard")
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboard;
    private final CurrentUserProvider currentUser;

    public DashboardController(DashboardService dashboard, CurrentUserProvider currentUser) {
        this.dashboard = dashboard;
        this.currentUser = currentUser;
    }

    @GetMapping("/summary")
    public DashboardSummary summary() {
        return dashboard.summary(currentUser.require());
    }
}
