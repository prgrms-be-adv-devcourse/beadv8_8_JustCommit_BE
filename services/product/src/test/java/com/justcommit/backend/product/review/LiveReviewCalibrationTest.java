package com.justcommit.backend.product.review;

import com.justcommit.backend.common.config.JpaAuditingConfig;
import com.justcommit.backend.product.banned.application.BannedListService;
import com.justcommit.backend.product.image.application.ImageData;
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
// species = 판매자가 고른 품종 이름(V4 품종 이름, "기타"면 기타), expected = 기대 결과(APPROVED · REJECTED · MANUAL)
// 결과는 콘솔 표와 build/review-calibration.csv (확신도 · 후보 · 판정 · 기대와 일치 여부)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ReviewJudgeProvider.class, BannedListService.class})
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

        List<String> lines = Files.readAllLines(dir.resolve("samples.csv"), StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            String[] col = line.split(",", -1);
            String imagesCol = col[0].trim();
            String speciesName = col[1].trim();
            String expected = col.length > 2 ? col[2].trim() : "";
            String title = col.length > 3 ? col[3].trim() : "";

            List<ImageData> images = new ArrayList<>();
            for (String file : imagesCol.split(";")) {
                Path p = dir.resolve(file.trim());
                String type = file.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
                images.add(new ImageData(Files.readAllBytes(p), type));
            }

            boolean imageFlagged = false;
            for (ImageData image : images) {
                imageFlagged |= moderation.isImageFlagged(image);
            }
            boolean textFlagged = !title.isEmpty() && moderation.isTextFlagged(title);
            PlantIdentification plant = haiku.identify(images, bannedNames);

            SellerSpecies seller = "기타".equals(speciesName)
                    ? SellerSpecies.otherSpecies()
                    : SellerSpecies.of(scientificByName.get(speciesName));
            ReviewVerdict verdict = judgeProvider.get().judge(new ModerationResult(imageFlagged, textFlagged), plant,
                    bannedListService.matchText(title, ""), seller);

            Function<Integer, String> cand = i -> i < plant.candidates().size()
                    ? describe(plant.candidates().get(i)) : "";
            String reason = verdict.rejectReason() != null ? verdict.rejectReason().name()
                    : verdict.manualReason() != null ? verdict.manualReason().name() : "";
            boolean ok = expected.isEmpty() || expected.equalsIgnoreCase(verdict.decision().name());
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

    private static String describe(Candidate c) {
        return c.scientificName() + " " + String.format("%.2f", c.confidence());
    }
}
