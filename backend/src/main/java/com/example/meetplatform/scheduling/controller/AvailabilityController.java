package com.example.meetplatform.scheduling.controller;

import com.example.meetplatform.common.security.CurrentUserProvider;
import com.example.meetplatform.scheduling.dto.AvailabilityDtos.AvailabilityView;
import com.example.meetplatform.scheduling.dto.AvailabilityDtos.ExceptionRequest;
import com.example.meetplatform.scheduling.dto.AvailabilityDtos.ExceptionView;
import com.example.meetplatform.scheduling.dto.AvailabilityDtos.UpdateAvailabilityRequest;
import com.example.meetplatform.scheduling.service.AvailabilityService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "Availability")
@RestController
@RequestMapping("/api/v1/availability")
public class AvailabilityController {

    private final AvailabilityService service;
    private final CurrentUserProvider currentUser;

    public AvailabilityController(AvailabilityService service, CurrentUserProvider currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public AvailabilityView get() {
        return service.get(currentUser.require().userId());
    }

    @PutMapping
    public AvailabilityView replace(@Valid @RequestBody UpdateAvailabilityRequest request) {
        return service.replace(currentUser.require(), request);
    }

    @GetMapping("/exceptions")
    public List<ExceptionView> exceptions(@RequestParam(required = false) LocalDate from,
                                          @RequestParam(required = false) LocalDate to) {
        return service.exceptions(currentUser.require().userId(), from, to);
    }

    @PostMapping("/exceptions")
    @ResponseStatus(HttpStatus.CREATED)
    public ExceptionView addException(@Valid @RequestBody ExceptionRequest request) {
        return service.addException(currentUser.require(), request);
    }

    @DeleteMapping("/exceptions/{exceptionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteException(@PathVariable UUID exceptionId) {
        service.deleteException(currentUser.require(), exceptionId);
    }
}
