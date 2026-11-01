package com.example.meetplatform.booking.controller;

import com.example.meetplatform.booking.dto.BookingDtos.BookingPageRequest;
import com.example.meetplatform.booking.dto.BookingDtos.BookingPageView;
import com.example.meetplatform.booking.dto.BookingDtos.PublicBookingPage;
import com.example.meetplatform.booking.dto.BookingDtos.SlotsResponse;
import com.example.meetplatform.booking.dto.BookingDtos.UpdateBookingPageRequest;
import com.example.meetplatform.booking.service.BookingPageService;
import com.example.meetplatform.common.observability.ClientInfo;
import com.example.meetplatform.common.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "Booking pages")
@RestController
@RequestMapping("/api/v1/booking-pages")
public class BookingPageController {

    private final BookingPageService service;
    private final CurrentUserProvider currentUser;

    public BookingPageController(BookingPageService service, CurrentUserProvider currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<BookingPageView> mine() {
        return service.mine(currentUser.require());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingPageView create(@Valid @RequestBody BookingPageRequest request) {
        return service.create(currentUser.require(), request);
    }

    @GetMapping("/{id}")
    public BookingPageView get(@PathVariable UUID id) {
        return service.get(currentUser.require(), id);
    }

    @PatchMapping("/{id}")
    public BookingPageView update(@PathVariable UUID id, @Valid @RequestBody UpdateBookingPageRequest request) {
        return service.update(currentUser.require(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(currentUser.require(), id);
    }

    @GetMapping("/public/{slug}")
    public PublicBookingPage publicPage(@PathVariable String slug) {
        return service.publicPage(slug);
    }

    @GetMapping("/public/{slug}/slots")
    public SlotsResponse slots(@PathVariable String slug, @RequestParam LocalDate from, @RequestParam LocalDate to,
                               @RequestParam String timeZone, HttpServletRequest http) {
        return service.publicSlots(slug, from, to, timeZone, ClientInfo.from(http).ipAddress());
    }
}
