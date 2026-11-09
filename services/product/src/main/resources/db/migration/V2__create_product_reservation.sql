-- 상품 예약 기록 (내부 API POST /internal/v1/products/reservations, ADR 0016)
-- orderId당 한 행. 결제 전 해제되면 RELEASED로 남아, 같은 orderId로 늦게 도착한 예약을 409로 막는다.
-- status에는 CHECK를 두지 않는다 (V1과 같은 규칙)

create table product_reservation (
    order_id   bigint not null,
    status     varchar(20) not null,
    created_at timestamp(6),
    updated_at timestamp(6),
    constraint pk_product_reservation primary key (order_id)
);
