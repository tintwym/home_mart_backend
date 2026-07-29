package dev.tintwym.home_mart_backend.service;

import dev.tintwym.home_mart_backend.entity.OrderEntity;
import dev.tintwym.home_mart_backend.entity.OrderItem;
import dev.tintwym.home_mart_backend.repository.CartItemRepository;
import dev.tintwym.home_mart_backend.repository.OrderItemRepository;
import dev.tintwym.home_mart_backend.repository.OrderRepository;
import com.stripe.model.checkout.Session;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderFulfillmentService {

    private static final Logger log = LoggerFactory.getLogger(OrderFulfillmentService.class);

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CartItemRepository cartItemRepository;
    private final StripeService stripeService;

    public OrderFulfillmentService(
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            CartItemRepository cartItemRepository,
            StripeService stripeService) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.cartItemRepository = cartItemRepository;
        this.stripeService = stripeService;
    }

    public boolean hasSoldConflictExcluding(String orderId) {
        for (OrderItem oi : orderItemRepository.findByOrderId(orderId)) {
            if (orderItemRepository.existsByListingIdAndOrder_StatusInAndOrder_IdNot(
                    oi.getListingId(), OrderStatuses.SOLD_OR_HELD, orderId)) {
                return true;
            }
        }
        return false;
    }

    /** Cancel older reserved holds for this buyer so they can start a fresh checkout. */
    @Transactional
    public void releaseBuyerReservations(String userId, String keepOrderId) {
        List<OrderEntity> reserved = orderRepository.findByUserIdAndStatusIn(
                userId, List.of(OrderStatuses.RESERVED));
        for (OrderEntity order : reserved) {
            if (keepOrderId != null && keepOrderId.equals(order.getId())) {
                continue;
            }
            order.setStatus(OrderStatuses.CANCELLED);
            orderRepository.save(order);
        }
    }

    @Transactional
    public void markReserved(OrderEntity order) {
        if (OrderStatuses.PENDING.equals(order.getStatus())
                || OrderStatuses.RESERVED.equals(order.getStatus())) {
            order.setStatus(OrderStatuses.RESERVED);
            orderRepository.save(order);
        }
    }

    /**
     * Mark a Stripe-paid order as paid, or refund (test mode) on conflict.
     *
     * @return outcome: paid | already_paid | conflict_refunded | conflict_no_refund | not_found | wrong_user
     */
    @Transactional
    public String fulfillStripePaidSession(Session session, String expectedUserId) {
        if (session == null || !"paid".equalsIgnoreCase(session.getPaymentStatus())) {
            return "not_paid";
        }
        String orderId = session.getMetadata() == null ? null : session.getMetadata().get("order_id");
        OrderEntity order = orderId == null
                ? orderRepository.findByStripeSessionId(session.getId()).orElse(null)
                : orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return "not_found";
        }
        if (expectedUserId != null && !expectedUserId.equals(order.getUserId())) {
            return "wrong_user";
        }
        if (OrderStatuses.PAID.equals(order.getStatus())
                || OrderStatuses.COMPLETED.equals(order.getStatus())) {
            return "already_paid";
        }

        if (hasSoldConflictExcluding(order.getId())) {
            boolean refunded = stripeService.refundCheckoutSession(session);
            order.setStatus(OrderStatuses.CANCELLED);
            order.setPaymentGateway("stripe_test");
            order.setPaymentReference(refunded ? "conflict_refunded" : "conflict_unresolved");
            order.setStripeSessionId(session.getId());
            orderRepository.save(order);
            log.warn("Stripe order {} conflict after pay; refunded={}", order.getId(), refunded);
            return refunded ? "conflict_refunded" : "conflict_no_refund";
        }

        order.setStatus(OrderStatuses.PAID);
        order.setStripeSessionId(session.getId());
        order.setPaymentGateway("stripe_test");
        order.setPaymentReference("test_checkout");
        orderRepository.save(order);
        cartItemRepository.deleteByUserId(order.getUserId());
        return "paid";
    }

    @Transactional
    public void markCompleted(OrderEntity order) {
        if (OrderStatuses.PAID.equals(order.getStatus())
                || OrderStatuses.ARRANGED.equals(order.getStatus())) {
            order.setStatus(OrderStatuses.COMPLETED);
            orderRepository.save(order);
        }
    }
}
