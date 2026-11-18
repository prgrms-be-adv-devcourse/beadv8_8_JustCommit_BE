package com.justcommit.backend.product.banned.domain;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

// 제목 · 설명에 금지 이름 · 금지어가 있는지
// 정규화: 소문자, 글자 · 숫자만 남김(띄어쓰기 · 문장부호 제거) → "아편 양귀비" · "아편양귀비"를 같게 봄
// 예외 단어(꽃양귀비 등)를 먼저 지우고 찾음. 지운 자리에는 구분자를 넣어 앞뒤 글자가 붙어 새 단어가 되지 않게 함
// 제목 · 설명은 각각 정규화한 뒤 구분자로 합침 → 사용자가 쓴 "|"는 문장부호로 지워지고, 제목 끝과 설명 앞이 붙지 않음
public final class BannedTextMatcher {

    public enum Result { BANNED, REVIEW, NONE }

    public record Match(Result result, String word) {
        static final Match NONE = new Match(Result.NONE, null);
    }

    private static final String CUT = "|";

    private final List<Word> bannedWords;
    private final List<Word> reviewWords;
    private final List<String> exceptionWords;

    public BannedTextMatcher(List<String> bannedWords, List<String> reviewWords, List<String> exceptionWords) {
        this.bannedWords = words(bannedWords);
        this.reviewWords = words(reviewWords);
        this.exceptionWords = words(exceptionWords).stream().map(Word::normalized).toList();
    }

    public Match match(String... texts) {
        String text = String.join(CUT, Arrays.stream(texts).filter(Objects::nonNull).map(BannedTextMatcher::normalize).toList());
        for (String exception : exceptionWords) {
            text = text.replace(exception, CUT);
        }
        for (Word w : bannedWords) {
            if (text.contains(w.normalized())) return new Match(Result.BANNED, w.original());
        }
        for (Word w : reviewWords) {
            if (text.contains(w.normalized())) return new Match(Result.REVIEW, w.original());
        }
        return Match.NONE;
    }

    // 글자 · 숫자만 남김 (구분자 "|"도 지움)
    static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    // 긴 단어부터 찾아 더 구체적인 이름을 결과로 돌려줌 (예: "나도풍란"이 "풍란"보다 먼저)
    private static List<Word> words(List<String> words) {
        return words.stream()
                .filter(w -> w != null && !w.isBlank())
                .map(w -> new Word(w, normalize(w)))
                .filter(w -> !w.normalized().isEmpty())
                .distinct()
                .sorted(Comparator.comparingInt((Word w) -> w.normalized().length()).reversed())
                .toList();
    }

    private record Word(String original, String normalized) {
    }
}
