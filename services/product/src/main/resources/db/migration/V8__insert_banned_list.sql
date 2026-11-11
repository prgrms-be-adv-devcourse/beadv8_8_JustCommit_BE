-- product 서비스 V8: 판매 금지 목록 데이터 (#342, docs/flows/image-review.md)
-- 원본: 자료/데이터/nongsaro/banned_species_draft.csv (118줄) · banned_keyword_draft.csv (12줄)에서 생성. 고칠 때는 원본 CSV를 같이 고치고 새 버전 파일로 바꾼다
-- 법령 원문 대조 2026-11-06: 야생생물법 시행규칙 별표1 멸종위기 Ⅰ급 13 · Ⅱ급 79, 마약류관리법 원료 식물 8, 생태계교란 생물 고시 18
-- '자생지'는 단독으로 오탐이 있어 반려 대신 관리자 확인(REVIEW)으로 둔다 (#342 결정)

insert into banned_species (scientific_name, taxon_rank, korean_name, basis, source, memo) values
    ('Cypripedium japonicum', 'SPECIES', '광릉요강꽃', 'KR_ENDANGERED_I', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Gastrochilus fuscopunctatus', 'SPECIES', '금자란', 'KR_ENDANGERED_I', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Sedirea japonica', 'SPECIES', '나도풍란', 'KR_ENDANGERED_I', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Euchresta japonica', 'SPECIES', '만년콩', 'KR_ENDANGERED_I', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Thrixspermum japonicum', 'SPECIES', '비자란', 'KR_ENDANGERED_I', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Diapensia lapponica var. obovata', 'INFRA', '암매', 'KR_ENDANGERED_I', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Mankyua chejuense', 'SPECIES', '제주고사리삼', 'KR_ENDANGERED_I', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Cymbidium lancifolium', 'SPECIES', '죽백란', 'KR_ENDANGERED_I', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Gastrochilus japonicus', 'SPECIES', '탐라란', 'KR_ENDANGERED_I', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Cypripedium guttatum', 'SPECIES', '털복주머니란', 'KR_ENDANGERED_I', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Neofinetia falcata', 'SPECIES', '풍란', 'KR_ENDANGERED_I', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Leontopodium coreanum var. hallaisanense', 'INFRA', '한라솜다리', 'KR_ENDANGERED_I', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Cymbidium kanran', 'SPECIES', '한란', 'KR_ENDANGERED_I', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Lychnis kiusiana', 'SPECIES', '가는동자꽃', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Euryale ferox', 'SPECIES', '가시연', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Eleutherococcus senticosus', 'SPECIES', '가시오갈피나무', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', '약용 재배(인공증식) 유통 많음 → 관리자 확인 예외 검토'),
    ('Nymphaea tetragona var. minima', 'INFRA', '각시수련', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Quercus gilva', 'SPECIES', '개가시나무', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Glaux maritima var. obtusifolia', 'INFRA', '갯봄맞이꽃', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Cyclosorus interruptus', 'SPECIES', '검은별고사리', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Neottianthe cucullata', 'SPECIES', '구름병아리난초', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Trientalis europaea subsp. arctica', 'INFRA', '기생꽃', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Drosera peltata var. nipponica', 'INFRA', '끈끈이귀개', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Mitella nuda', 'SPECIES', '나도범의귀', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Kirengeshoma koreana', 'SPECIES', '나도승마', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Zigadenus sibiricus', 'SPECIES', '나도여로', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Lilium dauricum', 'SPECIES', '날개하늘나리', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Viola mirabilis', 'SPECIES', '넓은잎제비꽃', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Rhododendron aureum', 'SPECIES', '노랑만병초', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Iris koreana', 'SPECIES', '노랑붓꽃', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Asplenium wrightii', 'SPECIES', '눈썹고사리', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Aster altaicus var. uchiyamae', 'INFRA', '단양쑥부쟁이', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Anagallidium dichotomum', 'SPECIES', '대성쓴풀', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Iris dichotoma', 'SPECIES', '대청부채', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Cymbidium macrorhizon', 'SPECIES', '대흥란', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Cicuta virosa', 'SPECIES', '독미나리', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Cremastra unguiculata', 'SPECIES', '두잎약난초', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Ranunculus trichophyllus var. kadzusensis', 'INFRA', '매화마름', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Lasianthus japonicus', 'SPECIES', '무주나무', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Ceratopteris thalictroides', 'SPECIES', '물고사리', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', '수족관 수초로 유통'),
    ('Lycopodiella cernua', 'SPECIES', '물석송', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Habenaria flagellifera', 'SPECIES', '방울난초', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Aconitum coreanum', 'SPECIES', '백부자', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Orobanche filicicola', 'SPECIES', '백양더부살이', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Kuhlhasseltia nakaiana', 'SPECIES', '백운란', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Cypripedium macranthos', 'SPECIES', '복주머니란', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Silene capitata', 'SPECIES', '분홍장구채', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Viburnum burejaeticum', 'SPECIES', '산분꽃나무', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Paeonia obovata', 'SPECIES', '산작약', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Saururus chinensis', 'SPECIES', '삼백초', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', '약용 재배(인공증식) 유통 많음 → 관리자 확인 예외 검토'),
    ('Woodwardia japonica', 'SPECIES', '새깃아재비', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Pterygopleurum neurophyllum', 'SPECIES', '서울개발나물', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Dendrobium moniliforme', 'SPECIES', '석곡', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Adenophora erecta', 'SPECIES', '선모시대', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Viola raddeana', 'SPECIES', '선제비꽃', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Cotoneaster wilsonii', 'SPECIES', '섬개야광나무', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Bupleurum latissimum', 'SPECIES', '섬시호', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Scrophularia takesimensis', 'SPECIES', '섬현삼', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Aconitum austrokoreense', 'SPECIES', '세뿔투구꽃', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Gymnadenia conopsea', 'SPECIES', '손바닥난초', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Psilotum nudum', 'SPECIES', '솔잎난', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Brasenia schreberi', 'SPECIES', '순채', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Calanthe aristulifera', 'SPECIES', '신안새우난초', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Pedicularis ishidoyana', 'SPECIES', '애기송이풀', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Thalictrum coreanum', 'SPECIES', '연잎꿩의다리', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Viola websteri', 'SPECIES', '왕제비꽃', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Cyrtosia septentrionalis', 'SPECIES', '으름난초', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Utricularia yakusimensis', 'SPECIES', '자주땅귀개', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Viola biflora', 'SPECIES', '장백제비꽃', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Dysophylla yatabeana', 'SPECIES', '전주물꼬리풀', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Amsonia elliptica', 'SPECIES', '정향풀', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Lychnis wilfordii', 'SPECIES', '제비동자꽃', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Iris laevigata', 'SPECIES', '제비붓꽃', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Menyanthes trifoliata', 'SPECIES', '조름나물', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Sarcandra glabra', 'SPECIES', '죽절초', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Cleisostoma scolopendrifolium', 'SPECIES', '지네발란', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Lycoris chinensis var. sinuolata', 'INFRA', '진노랑상사화', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Oberonia japonica', 'SPECIES', '차걸이란', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Halenia coreana', 'SPECIES', '참닻꽃', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Isoetes coreana', 'SPECIES', '참물부추', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Michelia compressa', 'SPECIES', '초령목', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Metanarthecium luteo-viride', 'SPECIES', '칠보치마', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Bulbophyllum drymoglossum', 'SPECIES', '콩짜개란', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Epilobium hirsutum', 'SPECIES', '큰바늘꽃', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Asplenium antiquum', 'SPECIES', '파초일엽', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Stellera chamaejasme', 'SPECIES', '피뿌리풀', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Pedicularis hallaisanensis', 'SPECIES', '한라송이풀', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Liparis auriculata', 'SPECIES', '한라옥잠난초', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Silene fasciculata', 'SPECIES', '한라장구채', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Habenaria radiata', 'SPECIES', '해오라비난초', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Bulbophyllum inconspicuum', 'SPECIES', '혹난초', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Arctous rubra', 'SPECIES', '홍월귤', 'KR_ENDANGERED_II', '국가법령정보센터 야생생물법 시행규칙 별표1 (2022.12.9 개정)', null),
    ('Cannabis sativa', 'SPECIES', '대마', 'NARCOTIC', '마약류 관리에 관한 법률 제2조 (법률 제21857호, 2026.10.2 시행)', '제2조제4호 대마초(Cannabis sativa L). 종자·뿌리·성숙한 줄기는 대마 정의에서 빠지지만 종자는 판매 형태 규칙으로 막힌다'),
    ('Papaver somniferum', 'SPECIES', '양귀비', 'NARCOTIC', '마약류 관리에 관한 법률 제2조 (법률 제21857호, 2026.10.2 시행)', '제2조제2호가목. 관상용 꽃양귀비(개양귀비 Papaver rhoeas 등)는 합법 → banned_keyword 예외 단어'),
    ('Papaver setigerum', 'SPECIES', '양귀비(세티게룸)', 'NARCOTIC', '마약류 관리에 관한 법률 제2조 (법률 제21857호, 2026.10.2 시행)', '제2조제2호가목 Papaver setigerum DC.'),
    ('Papaver bracteatum', 'SPECIES', '양귀비(브락테아툼)', 'NARCOTIC', '마약류 관리에 관한 법률 제2조 (법률 제21857호, 2026.10.2 시행)', '제2조제2호가목'),
    ('Erythroxylum', 'GENUS', '코카나무', 'NARCOTIC', '마약류 관리에 관한 법률 제2조 (법률 제21857호, 2026.10.2 시행)', '제2조제2호다목 에리드록시론속의 모든 식물 (속 단위)'),
    ('Lophophora williamsii', 'SPECIES', '페이오트', 'NARCOTIC', '마약류 관리에 관한 법률 시행령 별표3 향정신성의약품 가목 (대통령령 제36728호, 2026.10.2 시행)', '별표3 18. 선인장. 다육 시장에 "로포포라"로 유통될 수 있음. 같은 속 L. diffusa 등은 목록에 없음'),
    ('Mitragyna speciosa', 'SPECIES', '크라톰', 'NARCOTIC', '마약류 관리에 관한 법률 시행령 별표3 향정신성의약품 가목 (대통령령 제36728호, 2026.10.2 시행)', '별표3 25'),
    ('Salvia divinorum', 'SPECIES', '살비아 디비노럼', 'NARCOTIC', '마약류 관리에 관한 법률 시행령 별표4 향정신성의약품 나목 (대통령령 제36728호, 2026.10.2 시행)', '별표4 22. 한글 별칭에 "살비아"만 넣지 말 것 (살비아속 원예종은 합법)'),
    ('Ambrosia artemisiifolia', 'SPECIES', '돼지풀', 'INVASIVE', '생물다양성 보전 및 이용에 관한 법률 제24조, 생태계교란 생물 지정 고시 (환경부고시 제2025-165호, 2025.10.7 시행)', null),
    ('Ambrosia trifida', 'SPECIES', '단풍잎돼지풀', 'INVASIVE', '생물다양성 보전 및 이용에 관한 법률 제24조, 생태계교란 생물 지정 고시 (환경부고시 제2025-165호, 2025.10.7 시행)', null),
    ('Ageratina altissima', 'SPECIES', '서양등골나물', 'INVASIVE', '생물다양성 보전 및 이용에 관한 법률 제24조, 생태계교란 생물 지정 고시 (환경부고시 제2025-165호, 2025.10.7 시행)', null),
    ('Paspalum distichum var. indutum', 'INFRA', '털물참새피', 'INVASIVE', '생물다양성 보전 및 이용에 관한 법률 제24조, 생태계교란 생물 지정 고시 (환경부고시 제2025-165호, 2025.10.7 시행)', null),
    ('Paspalum distichum', 'SPECIES', '물참새피', 'INVASIVE', '생물다양성 보전 및 이용에 관한 법률 제24조, 생태계교란 생물 지정 고시 (환경부고시 제2025-165호, 2025.10.7 시행)', null),
    ('Solanum carolinense', 'SPECIES', '도깨비가지', 'INVASIVE', '생물다양성 보전 및 이용에 관한 법률 제24조, 생태계교란 생물 지정 고시 (환경부고시 제2025-165호, 2025.10.7 시행)', null),
    ('Rumex acetosella', 'SPECIES', '애기수영', 'INVASIVE', '생물다양성 보전 및 이용에 관한 법률 제24조, 생태계교란 생물 지정 고시 (환경부고시 제2025-165호, 2025.10.7 시행)', null),
    ('Sicyos angulatus', 'SPECIES', '가시박', 'INVASIVE', '생물다양성 보전 및 이용에 관한 법률 제24조, 생태계교란 생물 지정 고시 (환경부고시 제2025-165호, 2025.10.7 시행)', null),
    ('Hypochaeris radicata', 'SPECIES', '서양금혼초', 'INVASIVE', '생물다양성 보전 및 이용에 관한 법률 제24조, 생태계교란 생물 지정 고시 (환경부고시 제2025-165호, 2025.10.7 시행)', null),
    ('Aster pilosus', 'SPECIES', '미국쑥부쟁이', 'INVASIVE', '생물다양성 보전 및 이용에 관한 법률 제24조, 생태계교란 생물 지정 고시 (환경부고시 제2025-165호, 2025.10.7 시행)', null),
    ('Solidago altissima', 'SPECIES', '양미역취', 'INVASIVE', '생물다양성 보전 및 이용에 관한 법률 제24조, 생태계교란 생물 지정 고시 (환경부고시 제2025-165호, 2025.10.7 시행)', null),
    ('Lactuca scariola', 'SPECIES', '가시상추', 'INVASIVE', '생물다양성 보전 및 이용에 관한 법률 제24조, 생태계교란 생물 지정 고시 (환경부고시 제2025-165호, 2025.10.7 시행)', null),
    ('Spartina alterniflora', 'SPECIES', '갯줄풀', 'INVASIVE', '생물다양성 보전 및 이용에 관한 법률 제24조, 생태계교란 생물 지정 고시 (환경부고시 제2025-165호, 2025.10.7 시행)', null),
    ('Spartina anglica', 'SPECIES', '영국갯끈풀', 'INVASIVE', '생물다양성 보전 및 이용에 관한 법률 제24조, 생태계교란 생물 지정 고시 (환경부고시 제2025-165호, 2025.10.7 시행)', null),
    ('Humulus japonicus', 'SPECIES', '환삼덩굴', 'INVASIVE', '생물다양성 보전 및 이용에 관한 법률 제24조, 생태계교란 생물 지정 고시 (환경부고시 제2025-165호, 2025.10.7 시행)', null),
    ('Alliaria petiolata', 'SPECIES', '마늘냉이', 'INVASIVE', '생물다양성 보전 및 이용에 관한 법률 제24조, 생태계교란 생물 지정 고시 (환경부고시 제2025-165호, 2025.10.7 시행)', null),
    ('Parthenium hysterophorus', 'SPECIES', '돼지풀아재비', 'INVASIVE', '생물다양성 보전 및 이용에 관한 법률 제24조, 생태계교란 생물 지정 고시 (환경부고시 제2025-165호, 2025.10.7 시행)', null),
    ('Ludwigia peploides', 'SPECIES', '물여뀌바늘', 'INVASIVE', '생물다양성 보전 및 이용에 관한 법률 제24조, 생태계교란 생물 지정 고시 (환경부고시 제2025-165호, 2025.10.7 시행)', null);

-- 별칭: 학명 동의어(최신 학명 · 옛 학명)는 SCIENTIFIC, 한글 이름은 KOREAN
insert into banned_species_alias (banned_species_id, alias, alias_type) select id, 'Saccolabium matsuran', 'SCIENTIFIC' from banned_species where scientific_name = 'Gastrochilus fuscopunctatus';
insert into banned_species_alias (banned_species_id, alias, alias_type) select id, 'Phalaenopsis japonica', 'SCIENTIFIC' from banned_species where scientific_name = 'Sedirea japonica';
insert into banned_species_alias (banned_species_id, alias, alias_type) select id, 'Aerides japonica', 'SCIENTIFIC' from banned_species where scientific_name = 'Sedirea japonica';
insert into banned_species_alias (banned_species_id, alias, alias_type) select id, 'Sarcochilus japonicus', 'SCIENTIFIC' from banned_species where scientific_name = 'Thrixspermum japonicum';
insert into banned_species_alias (banned_species_id, alias, alias_type) select id, 'Vanda falcata', 'SCIENTIFIC' from banned_species where scientific_name = 'Neofinetia falcata';
insert into banned_species_alias (banned_species_id, alias, alias_type) select id, 'Acanthopanax senticosus', 'SCIENTIFIC' from banned_species where scientific_name = 'Eleutherococcus senticosus';
insert into banned_species_alias (banned_species_id, alias, alias_type) select id, 'Gymnadenia cucullata', 'SCIENTIFIC' from banned_species where scientific_name = 'Neottianthe cucullata';
insert into banned_species_alias (banned_species_id, alias, alias_type) select id, 'Vexillabium yakusimense var. nakaianum', 'SCIENTIFIC' from banned_species where scientific_name = 'Kuhlhasseltia nakaiana';
insert into banned_species_alias (banned_species_id, alias, alias_type) select id, 'Arctous alpinus var. japonicus', 'SCIENTIFIC' from banned_species where scientific_name = 'Arctous rubra';
insert into banned_species_alias (banned_species_id, alias, alias_type) select id, '마리화나', 'KOREAN' from banned_species where scientific_name = 'Cannabis sativa';
insert into banned_species_alias (banned_species_id, alias, alias_type) select id, '대마초', 'KOREAN' from banned_species where scientific_name = 'Cannabis sativa';
insert into banned_species_alias (banned_species_id, alias, alias_type) select id, '칸나비스', 'KOREAN' from banned_species where scientific_name = 'Cannabis sativa';
insert into banned_species_alias (banned_species_id, alias, alias_type) select id, '앵속', 'KOREAN' from banned_species where scientific_name = 'Papaver somniferum';
insert into banned_species_alias (banned_species_id, alias, alias_type) select id, '아편꽃', 'KOREAN' from banned_species where scientific_name = 'Papaver somniferum';
insert into banned_species_alias (banned_species_id, alias, alias_type) select id, '아편 양귀비', 'KOREAN' from banned_species where scientific_name = 'Papaver somniferum';
insert into banned_species_alias (banned_species_id, alias, alias_type) select id, '코카', 'KOREAN' from banned_species where scientific_name = 'Erythroxylum';
insert into banned_species_alias (banned_species_id, alias, alias_type) select id, '코카관목', 'KOREAN' from banned_species where scientific_name = 'Erythroxylum';
insert into banned_species_alias (banned_species_id, alias, alias_type) select id, '페요테', 'KOREAN' from banned_species where scientific_name = 'Lophophora williamsii';
insert into banned_species_alias (banned_species_id, alias, alias_type) select id, '로포포라 윌리암시', 'KOREAN' from banned_species where scientific_name = 'Lophophora williamsii';
insert into banned_species_alias (banned_species_id, alias, alias_type) select id, '로포포라 윌리엄시', 'KOREAN' from banned_species where scientific_name = 'Lophophora williamsii';
insert into banned_species_alias (banned_species_id, alias, alias_type) select id, '크라톰나무', 'KOREAN' from banned_species where scientific_name = 'Mitragyna speciosa';
insert into banned_species_alias (banned_species_id, alias, alias_type) select id, '디비노럼', 'KOREAN' from banned_species where scientific_name = 'Salvia divinorum';

-- 금지어: BANNED 반려, REVIEW 관리자 확인, EXCEPTION 금지 이름이 들어 있어도 넘어가는 단어
insert into banned_keyword (keyword, keyword_type, reason) values
    ('꽃양귀비', 'EXCEPTION', '"양귀비"(Papaver somniferum 한글 이름)가 들어 있지만 관상용 개양귀비(Papaver rhoeas)를 부르는 말'),
    ('개양귀비', 'EXCEPTION', '관상용 Papaver rhoeas. 마약류 아님'),
    ('아이슬란드양귀비', 'EXCEPTION', '관상용 Papaver nudicaule. 마약류관리법 제2조의 양귀비 3종이 아님'),
    ('캘리포니아양귀비', 'EXCEPTION', '금영화(Eschscholzia californica). 양귀비속도 아님'),
    ('금영화', 'EXCEPTION', '캘리포니아양귀비의 다른 이름. 지금은 금지 이름과 겹치지 않지만 별칭 추가 대비'),
    ('서양복주머니란', 'EXCEPTION', '"복주머니란"(멸종위기 Ⅱ급)이 들어 있지만 파피오페딜럼(Paphiopedilum, 금지 아님)을 부르는 시장 이름'),
    ('코카콜라', 'EXCEPTION', '"코카"(코카나무 별칭)가 들어 있는 흔한 단어. 화분·병 재활용 글에서 나옴'),
    ('산채', 'BANNED', '야생에서 캔 식물. 품종과 상관없이 자생지 채취품'),
    ('산에서 캔', 'BANNED', '야생 채취를 글로 밝힌 경우'),
    ('자생지', 'REVIEW', '"자생지에서 채취" 같은 글. 단독으로는 오탐이 있을 수 있어 관리자 확인으로 낮출지 구현 때 정함'),
    ('대마씨', 'BANNED', '대마 종자. 대마 정의에서는 빠지지만 판매 형태(씨앗) 금지와 겹쳐 글로도 잡음'),
    ('삽수', 'BANNED', '판매 불가 형태(꺾꽂이용 줄기)를 글로 밝힌 경우');
