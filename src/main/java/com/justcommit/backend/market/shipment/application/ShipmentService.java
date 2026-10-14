package com.justcommit.backend.market.shipment.application;

import com.justcommit.backend.market.OrderQuery;
import com.justcommit.backend.market.OrderResult;
import com.justcommit.backend.market.OrdersItemResult;
import com.justcommit.backend.market.SellerOrderResult;
import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierBookingException;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierBookingRequest;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierBookingResponse;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierClient;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentRepository;
import com.justcommit.backend.member.MemberAddress;
import com.justcommit.backend.member.MemberQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final ShipmentRepository shipmentRepository;
    private final CourierClient courierClient;
    private final OrderQuery orderQuery;
    private final MemberQuery memberQuery;

    public void createShipments(Long orderId) {
        OrderResult order = orderQuery.getOrder(orderId);

        for (SellerOrderResult sellerOrder : order.sellerOrders()) {
            createAndBook(order, sellerOrder);
        }
    }

    private void createAndBook(OrderResult order, SellerOrderResult sellerOrder) {
        Shipment shipment = shipmentRepository.save(new Shipment(sellerOrder.id()));

        shipment.assignCourierOrderNo(LocalDate.now(SEOUL));
        shipmentRepository.save(shipment);

        MemberAddress seller = memberQuery.getDefaultAddress(sellerOrder.sellerId());
        CourierBookingRequest request = new CourierBookingRequest(
                new CourierBookingRequest.Party(seller.recipientName(),
                        seller.recipientPhone(),
                        fullAddress(seller.address1(), seller.address2())),
                new CourierBookingRequest.Party(order.recipientName(),
                        order.recipientPhone(),
                        fullAddress(order.address1(), order.address2())),
                itemSummary(sellerOrder.items())
        );

        try {
            CourierBookingResponse response = courierClient.book(shipment.getCourierOrderNo(), request);
            shipment.book(response.trackingNo(), response.bookedAt().atZoneSameInstant(SEOUL).toLocalDateTime());
            shipmentRepository.save(shipment);
        } catch (CourierBookingException e) {
            if (e.isRetryable()) {
                log.warn("택배 예약 실패, 나중에 다시 시도: shipmentId={}", shipment.getId(), e);
            } else {
                log.error("택배 예약 실패, 확인 필요: shipmentId={}", shipment.getId(), e);
            }
        }
    }

    private String itemSummary(List<OrdersItemResult> items) {
        String first = items.getFirst().productName();
        if (items.size() == 1) {
            return String.format("%s", first);
        }
        return String.format("%s 외 %d건", first, items.size() - 1);
    }

    private String fullAddress(String address1, String address2) {
        if (address2 == null || address2.isBlank()) {
            return address1;
        }
        return address1 + " " + address2;
    }
}


