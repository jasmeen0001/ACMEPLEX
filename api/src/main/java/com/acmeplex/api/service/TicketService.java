package com.acmeplex.api.service;

import com.acmeplex.api.dto.CreateTicketRequestDto;
import com.acmeplex.api.dto.CreditVoucherDto;
import com.acmeplex.api.dto.TicketDto;
import com.acmeplex.api.mappers.CreditVoucherMapper;
import com.acmeplex.api.mappers.TicketMapper;
import com.acmeplex.api.model.*;
import com.acmeplex.api.repository.CreditVoucherRepository;
import com.acmeplex.api.repository.RegisteredUserRepository;
import com.acmeplex.api.repository.ShowtimeSeatRepository;
import com.acmeplex.api.repository.TicketRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class TicketService {

    public static final int CANCELLATION_CUTOFF_HOURS = 72;
    public static final double DEDUCTION_PERCENT_GUEST_USERS = 0.15;
    public static final int CREDIT_VOUCHER_VALIDITY_PERIOD_IN_YEARS = 1;
    private final TicketRepository ticketRepository;
    private final CreditVoucherRepository creditVoucherRepository;
    private final RegisteredUserRepository registeredUserRepository;
    private final ShowtimeSeatRepository showtimeSeatRepository;
    private final UserNotificationService userNotificationService;

    @Autowired
    public TicketService(TicketRepository ticketRepository,
                         CreditVoucherRepository creditVoucherRepository,
                         RegisteredUserRepository registeredUserRepository,
                         ShowtimeSeatRepository showtimeSeatRepository,
                         UserNotificationService userNotificationService) {
        this.ticketRepository = ticketRepository;
        this.creditVoucherRepository = creditVoucherRepository;
        this.registeredUserRepository = registeredUserRepository;
        this.showtimeSeatRepository = showtimeSeatRepository;
        this.userNotificationService = userNotificationService;
    }

    private static String generateCouponCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    public TicketDto reserveTicket(CreateTicketRequestDto reserveTicketRequestDto) {
        Long showtimeId = reserveTicketRequestDto.getShowtimeId();
        Long seatId = reserveTicketRequestDto.getSeatId();
        String customerName = reserveTicketRequestDto.getCustomerName();
        String customerEmail = reserveTicketRequestDto.getCustomerEmail();

        ShowtimeSeat showtimeSeat = getShowtimeSeat(showtimeId, seatId);

        Double ticketPrice = calculateTicketPrice(customerEmail, showtimeSeat.getSeat());
        TicketStatus ticketStatus = TicketStatus.BOOKED;
        if (ticketPrice <= 0) {
            ticketStatus = TicketStatus.CONFIRMED;
        }

        // Mark seat as reserved
        showtimeSeat.setIsReserved(true);
        showtimeSeatRepository.save(showtimeSeat);
        Ticket newTicket = ticketRepository.save(
                new Ticket(customerName, customerEmail, ticketPrice, showtimeSeat.getSeat(), showtimeSeat.getShowtime(), ticketStatus));

        //Notification with ticket and receipt details will be sent after successfully processing the payment
        //userNotificationService.sendTicketAndReceiptDetails(newTicket);

        return TicketMapper.toTicketDto(newTicket);
    }

    private ShowtimeSeat getShowtimeSeat(Long showtimeId, Long seatId) throws ResponseStatusException {
        ShowtimeSeat showtimeSeat = showtimeSeatRepository.findByShowtimeIdAndSeatId(showtimeId, seatId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "ShowtimeSeat not found for showtimeId: " + showtimeId + " and seatId: " + seatId));
        if (showtimeSeat.getIsReserved()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Seat " + seatId + " is already reserved");
        }
        return showtimeSeat;
    }

    private Double calculateTicketPrice(String customerEmail, Seat seat) {
        Double ticketPrice = seat.getPrice();
        // Apply unused credit vouchers
        List<CreditVoucher> vouchers = creditVoucherRepository.findByCustomerEmailAndIsUsedFalse(customerEmail);
        for (CreditVoucher voucher : vouchers) {
            if (ticketPrice <= 0) break; // No need to apply more vouchers if price is zero

            double voucherAmount = voucher.getAmount();
            if (voucherAmount >= ticketPrice) {
                // Fully cover the ticket price with this voucher
                voucher.setAmount(voucherAmount - ticketPrice);
                ticketPrice = 0.0;
            } else {
                // Partially cover the ticket price with this voucher
                ticketPrice -= voucherAmount;
                voucher.setAmount(0.0);
            }

            // Mark voucher as used if fully utilized
            if (voucher.getAmount() == 0) {
                voucher.setIsUsed(true);
            }
            creditVoucherRepository.save(voucher);
        }
        return ticketPrice;
    }

    public List<TicketDto> getAllTickets() {
        return getTicketDtos(ticketRepository.findAll());
    }

    public List<TicketDto> getTicketsByCustomerEmail(String customerEmail) {
        return getTicketDtos(ticketRepository.findByCustomerEmail(customerEmail));
    }

    private List<TicketDto> getTicketDtos(List<Ticket> ticketList) {
        List<TicketDto> tickets = new ArrayList<>();
        for (Ticket item : ticketList) {
            tickets.add(TicketMapper.toTicketDto(item));
        }
        return tickets;
    }

    // Retrieve ticket details by ID
    public TicketDto getTicketDetails(Long ticketId) throws ResponseStatusException {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, String.format("Ticket %d not found", ticketId)));
        return TicketMapper.toTicketDto(ticket);
    }

    public CreditVoucherDto cancelTicket(Long ticketId) throws ResponseStatusException {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, String.format("Ticket %d not found", ticketId)));

        if (ticket.getStatus() == TicketStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.format("Ticket %d is already cancelled", ticketId));
        }

        // Check if cancellation is allowed (72 hours before showtime)
        LocalDateTime now = LocalDateTime.now();
        isCancellationAllowed(ticket, now);

        if (ticket.getStatus() == TicketStatus.BOOKED && ticket.getPaymentReceipt() == null) {
            makeSeatAvailable(ticket);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.format("Payment is yet to be made for ticket %d.", ticketId));
        }

        double refundAmount = calculateRefundAmount(ticket);

        ticket.setStatus(TicketStatus.CANCELLED);
        ticketRepository.save(ticket);

        makeSeatAvailable(ticket);

        CreditVoucher creditVoucher = new CreditVoucher(
                refundAmount,
                now,
                now.plusYears(CREDIT_VOUCHER_VALIDITY_PERIOD_IN_YEARS),
                ticket.getCustomerEmail(),
                generateCouponCode(),
                Boolean.FALSE,
                ticket
        );
        CreditVoucher savedCreditVoucher = creditVoucherRepository.save(creditVoucher);

        userNotificationService.sendCreditVoucherDetails(savedCreditVoucher);

        return CreditVoucherMapper.toCreditVoucherDto(savedCreditVoucher);
    }

    private void makeSeatAvailable(Ticket ticket) throws ResponseStatusException {
        Long showtimeId = ticket.getShowtime().getId();
        Long seatId = ticket.getSeat().getId();
        ShowtimeSeat showtimeSeat = showtimeSeatRepository.findByShowtimeIdAndSeatId(showtimeId, seatId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ShowtimeSeat not found for showtimeId: " + showtimeId + " and seatId: " + seatId));

        showtimeSeat.setIsReserved(false);
        showtimeSeatRepository.save(showtimeSeat);
    }

    private void isCancellationAllowed(Ticket ticket, LocalDateTime current_time) throws ResponseStatusException {
        LocalDateTime showtimeStart = LocalDateTime.of(ticket.getShowtime().getDate(), ticket.getShowtime().getTime());
        long hoursUntilShowtime = Duration.between(current_time, showtimeStart).toHours();

        if (hoursUntilShowtime < CANCELLATION_CUTOFF_HOURS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cancellation is not allowed within 72 hours of the showtime.");
        }
    }

    private double calculateRefundAmount(Ticket ticket) throws ResponseStatusException {
        PaymentReceipt paymentReceipt = ticket.getPaymentReceipt();
        if (paymentReceipt == null || paymentReceipt.getStatus() != PaymentStatus.SUCCESS) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No successful payment found for this ticket.");
        }
        // Retrieve the user based on customer email
        String customerEmail = ticket.getCustomerEmail();
        RegisteredUser user = registeredUserRepository.findByEmail(customerEmail).orElse(null);
        double originalAmount = paymentReceipt.getAmount();
        if (user != null && !user.isMembershipExpired()) {
            return originalAmount;
        } else {
            double cancellationFee = originalAmount * DEDUCTION_PERCENT_GUEST_USERS;
            return originalAmount - cancellationFee;
        }
    }
}