-- product 서비스 V11: 「기타」 품종 (#340)
-- 품종 목록에 없는 식물을 팔 때 고르는 품종. 사진 검수는 항상 관리자 확인(OTHER_SPECIES)으로 간다 (docs/flows/image-review.md)
-- 학명 · 관리 속성 · 카테고리 · 사진은 없다. 카테고리가 없어 카테고리로 찾을 때는 나오지 않는다
-- id 218은 V4 마지막 id(217) 다음 번호로 고정한다. 코드(Species.OTHER_ID)와 추천 쪽이 이 id로 「기타」를 알아본다

INSERT INTO species (id, name, scientific_name, light, watering, difficulty, color, category, created_at, updated_at)
VALUES (218, '기타', NULL, NULL, NULL, NULL, NULL, NULL, now(), now());

SELECT setval(pg_get_serial_sequence('species', 'id'), (SELECT MAX(id) FROM species));
