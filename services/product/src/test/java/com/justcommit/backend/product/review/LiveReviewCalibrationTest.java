package com.justcommit.backend.product.review;

import com.justcommit.backend.common.config.JpaAuditingConfig;
import com.justcommit.backend.product.banned.application.BannedListService;
import com.justcommit.backend.product.config.ClockConfig;
import com.justcommit.backend.product.image.application.ImageData;
import com.justcommit.backend.product.review.application.ReviewEngineException;
import com.justcommit.backend.product.review.application.ReviewJudgeProvider;
import com.justcommit.backend.product.review.domain.Candidate;
import com.justcommit.backend.product.review.domain.ModerationResult;
import com.justcommit.backend.product.review.domain.PlantIdentification;
import com.justcommit.backend.product.review.domain.ReviewVerdict;
import com.justcommit.backend.product.review.domain.SellerSpecies;
import com.justcommit.backend.product.review.infrastructure.HaikuPlantIdentifier;
import com.justcommit.backend.product.review.infrastructure.OpenAiModerationClient;
import com.justcommit.backend.product.species.domain.Species;
import com.justcommit.backend.product.species.infrastructure.SpeciesRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// 기준값 0.3 · 0.7 실측용 (실제 OpenAI · Anthropic 호출, 비용 발생). CI · 평소 빌드에서는 건너뜀
// 실행: REVIEW_SAMPLES_DIR · OPENAI_API_KEY · ANTHROPIC_API_KEY 환경변수를 넣고
//   ./gradlew :services:product:test --tests '*LiveReviewCalibrationTest'
// 표본 폴더에 사진과 samples.csv (머리줄 포함):
//   images,species,expected,title
//   monstera1.jpg;monstera2.jpg,몬스테라,APPROVED,
//   seed.jpg,몬스테라,REJECTED,
//   blur.jpg,기타,MANUAL,
// species = 판매자가 고른 품종 이름(V4 품종 이름 또는 학명, "기타"면 기타), expected = 기대 결과(APPROVED · REJECTED · MANUAL, | 로 여러 개)
// samples.csv가 없으면 실험 폴더를 그대로 읽음: 3차(드라이브 plant-test-v3: photos/ + labels_v3.csv)
// 또는 실판매(심폴 plant-test-simpol: photos/ + labels_simpol.csv, 상품 제목도 글 검사에 넣음)
// 결과는 콘솔 표와 build/review-calibration.csv (확신도 · 후보 · 판정 · 기대와 일치 여부)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ClockConfig.class, ReviewJudgeProvider.class, BannedListService.class})
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "REVIEW_SAMPLES_DIR", matches = ".+")
class LiveReviewCalibrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired ReviewJudgeProvider judgeProvider;
    @Autowired BannedListService bannedListService;
    @Autowired SpeciesRepository speciesRepository;

    @Test
    void 실제_사진으로_기준값_확인() throws IOException {
        Path dir = Path.of(System.getenv("REVIEW_SAMPLES_DIR"));
        String openAiKey = System.getenv().getOrDefault("OPENAI_API_KEY", "");
        String anthropicKey = System.getenv().getOrDefault("ANTHROPIC_API_KEY", "");
        assertThat(openAiKey).as("OPENAI_API_KEY").isNotBlank();
        assertThat(anthropicKey).as("ANTHROPIC_API_KEY").isNotBlank();

        OpenAiModerationClient moderation = new OpenAiModerationClient(RestClient.builder(), "https://api.openai.com",
                openAiKey, Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(20));
        HaikuPlantIdentifier haiku = new HaikuPlantIdentifier(anthropicKey);

        Map<String, String> scientificByName = speciesRepository.findAll().stream()
                .filter(s -> s.getScientificName() != null)
                .collect(Collectors.toMap(Species::getName, Species::getScientificName, (a, b) -> a));
        List<String> bannedNames = bannedListService.promptNames();

        List<String> out = new ArrayList<>();
        out.add("images,species,expected,top1,top1_confidence,top2,top3,plant,sale_form,decision,reason,detail,match");
        int total = 0;
        int matched = 0;

        for (String[] row : readSamples(dir)) {
            String imagesCol = row[0];
            String speciesName = row[1];
            String expected = row[2];
            String title = row[3];
            Path photoDir = Files.isDirectory(dir.resolve("photos")) ? dir.resolve("photos") : dir;

            List<ImageData> images = new ArrayList<>();
            for (String file : imagesCol.split(";")) {
                Path p = photoDir.resolve(file.trim());
                String type = file.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
                images.add(new ImageData(Files.readAllBytes(p), type));
            }

            boolean imageFlagged = false;
            boolean textFlagged;
            PlantIdentification plant;
            try {
                for (ImageData image : images) {
                    imageFlagged |= moderation.isImageFlagged(image);
                }
                textFlagged = !title.isEmpty() && moderation.isTextFlagged(title);
                plant = haiku.identify(images, bannedNames);
            } catch (ReviewEngineException e) {
                // 한 건 실패로 전체를 멈추지 않고 기록만 (재처리 대상인 엔진 오류)
                total++;
                out.add(String.join(",", imagesCol, speciesName, expected, "", "", "", "", "", "",
                        "ENGINE_ERROR", "", e.getMessage().replace(',', ' '), "X"));
                continue;
            }

            // 한글 품종 이름이면 학명으로 바꾸고, 아니면 학명으로 그대로 씀 (실험 라벨은 학명)
            SellerSpecies seller = "기타".equals(speciesName) || "OTHER".equalsIgnoreCase(speciesName)
                    ? SellerSpecies.otherSpecies()
                    : SellerSpecies.of(scientificByName.getOrDefault(speciesName, speciesName));
            ReviewVerdict verdict = judgeProvider.get().judge(new ModerationResult(imageFlagged, textFlagged), plant,
                    bannedListService.matchText(title, ""), seller);

            Function<Integer, String> cand = i -> i < plant.candidates().size()
                    ? describe(plant.candidates().get(i)) : "";
            String reason = verdict.rejectReason() != null ? verdict.rejectReason().name()
                    : verdict.manualReason() != null ? verdict.manualReason().name() : "";
            boolean ok = expected.isEmpty() || List.of(expected.split("\\|")).contains(verdict.decision().name());
            total++;
            if (ok) {
                matched++;
            }
            Candidate top = plant.candidates().isEmpty() ? null : plant.candidates().get(0);
            out.add(String.join(",", imagesCol, speciesName, expected,
                    top == null ? "" : top.scientificName(),
                    top == null ? "" : String.format("%.2f", top.confidence()),
                    cand.apply(1), cand.apply(2), String.valueOf(plant.plant()),
                    plant.saleForm() == null ? "" : plant.saleForm().name(),
                    verdict.decision().name(), reason, verdict.detail() == null ? "" : verdict.detail().replace(',', ' '),
                    ok ? "O" : "X"));
        }

        Path result = Path.of("build", "review-calibration.csv");
        Files.createDirectories(result.getParent());
        Files.write(result, out, StandardCharsets.UTF_8);
        System.out.println("\n=== 사진 검수 기준값 실측 (" + matched + "/" + total + " 기대와 일치) ===");
        out.forEach(System.out::println);
        System.out.println("결과 파일: " + result.toAbsolutePath());
    }

    // 표본 한 줄 = {사진들, 판매자 품종, 기대 결과(| 로 여러 개), 제목}
    // samples.csv가 있으면 그것을, 없으면 실험 라벨(labels_v3.csv · labels_simpol.csv)을 읽고 묶음(group)으로 기대 결과를 정함
    private static List<String[]> readSamples(Path dir) throws IOException {
        List<String[]> rows = new ArrayList<>();
        Path samples = dir.resolve("samples.csv");
        if (Files.exists(samples)) {
            List<String> lines = Files.readAllLines(samples, StandardCharsets.UTF_8);
            for (String line : lines.subList(1, lines.size())) {
                if (line.isBlank()) {
                    continue;
                }
                String[] c = line.split(",", -1);
                rows.add(new String[]{c[0].trim(), c[1].trim(), c.length > 2 ? c[2].trim() : "",
                        c.length > 3 ? c[3].trim() : ""});
            }
            return rows;
        }
        // 실험 라벨: 3차(labels_v3.csv, Commons) 또는 실판매(labels_simpol.csv, 심폴 · product_title 열 있음)
        // 머리줄 이름으로 열을 찾음. file,group,...,seller_species[,subgroup,needs_check,product_title]
        Path labels = Files.exists(dir.resolve("labels_v3.csv")) ? dir.resolve("labels_v3.csv") : dir.resolve("labels_simpol.csv");
        List<String> lines = Files.readAllLines(labels, StandardCharsets.UTF_8);
        List<String> header = parseCsvLine(lines.get(0).replace("\uFEFF", ""));
        int fileCol = header.indexOf("file");
        int groupCol = header.indexOf("group");
        int sellerCol = header.indexOf("seller_species");
        int titleCol = header.indexOf("product_title");
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            List<String> c = parseCsvLine(line);
            String group = c.get(groupCol).trim();
            String seller = c.get(sellerCol).trim();
            String expected = switch (group) {
                case "market" -> "APPROVED";
                case "bad_photo" -> "APPROVED|MANUAL";               // 흐린 · 어두운 사진은 관리자 확인도 허용
                case "mismatch" -> "MANUAL";                          // 품종 불일치
                case "not_plant", "form" -> "REJECTED";               // 식물 아님 · 씨앗 · 삽수 · 구근
                case "banned", "disguise" -> "REJECTED|MANUAL";       // 금지 품종은 통과만 아니면 됨
                case "lookalike" -> "OTHER".equals(seller) ? "MANUAL" : "APPROVED|MANUAL";
                default -> "";
            };
            String title = titleCol >= 0 && titleCol < c.size() ? c.get(titleCol).trim() : "";
            rows.add(new String[]{c.get(fileCol).trim(), seller, expected, title});
        }
        return rows;
    }

    // 따옴표로 감싼 칸(쉼표 · "" 포함)을 처리하는 CSV 한 줄 파서. 실판매 상품 제목에 쉼표가 들어 있음
    private static List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    private static String describe(Candidate c) {
        return c.scientificName() + " " + String.format("%.2f", c.confidence());
    }
}
