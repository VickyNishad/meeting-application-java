package com.example.meetplatform.booking.controller;

import com.example.meetplatform.booking.dto.BookingDtos.BookingView;
import com.example.meetplatform.booking.dto.BookingDtos.CancelBookingRequest;
import com.example.meetplatform.booking.dto.BookingDtos.CreateBookingRequest;
import com.example.meetplatform.booking.dto.BookingDtos.RescheduleRequest;
import com.example.meetplatform.booking.service.BookingService;
import com.example.meetplatform.common.idempotency.IdempotencyService;
import com.example.meetplatform.common.observability.ClientInfo;
import com.example.meetplatform.common.response.PageResponse;
import com.example.meetplatform.common.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Bookings")
@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService service;
    private final CurrentUserProvider currentUser;
    private final IdempotencyService idempotency;

    public BookingController(BookingService service, CurrentUserProvider currentUser, IdempotencyService idempotency) {
        this.service = service;
        this.currentUser = currentUser;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateBookingRequest request,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                    HttpServletRequest http) {
        String ip = ClientInfo.from(http).ipAddress();
        return idempotency.execute("bookings", idempotencyKey, request,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(service.create(request, ip)));
    }

    @GetMapping
    public PageResponse<BookingView> forHost(@RequestParam(defaultValue = "upcoming") String scope,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        return service.forHost(currentUser.require(), scope, page, size);
    }

    @GetMapping("/{bookingId}")
    public BookingView get(@PathVariable UUID bookingId) {
        return service.get(currentUser.require(), bookingId);
    }

    @PostMapping("/{bookingId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelByHost(@PathVariable UUID bookingId,
                             @Valid @RequestBody(required = false) CancelBookingRequest request) {
        service.cancelByHost(currentUser.require(), bookingId, request == null ? null : request.reason());
    }

    @GetMapping("/manage/{token}")
    public BookingView manage(@PathVariable String token) {
        return service.byManageToken(token);
    }

    @PostMapping("/manage/{token}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelByInvitee(@PathVariable String token,
                                @Valid @RequestBody(required = false) CancelBookingRequest request) {
        service.cancelByInvitee(token, request == null ? null : request.reason());
    }

    @PostMapping("/manage/{token}/reschedule")
    public BookingView reschedule(@PathVariable String token, @Valid @RequestBody RescheduleRequest request) {
        return service.reschedule(token, request.startTime());
    }
}
