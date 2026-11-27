package com.capstone.triplea.advertisement.image;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

// agent가 생성한 광고 이미지 파일 (agent와 api가 함께 마운트하는 공유 볼륨)
// AdContent.imageUrl에는 "/api/v1/ad-images/{파일명}" 형식의 상대 URL이 담김
@Component
public class AdImageStorage {

    public static final String URL_PREFIX = "/api/v1/ad-images/";

    // agent가 만드는 파일명 형식 (32자리 hex + .jpg). 이 형식만 허용해 경로 조작을 막음
    private static final Pattern FILE_NAME = Pattern.compile("^[0-9a-f]{32}\\.jpg$");

    private final Path dir;

    public AdImageStorage(@Value("${ad.image.dir}") String dir) {
        this.dir = Path.of(dir);
    }

    // 파일명으로 이미지 파일 경로를 찾음. 형식이 다르거나 파일이 없으면 빈 값
    public Optional<Path> find(String fileName) {
        if (fileName == null || !FILE_NAME.matcher(fileName).matches()) {
            return Optional.empty();
        }
        Path path = dir.resolve(fileName);
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    // imageUrl이 가리키는 이미지 바이트 (5단계 Facebook 업로드용)
    // 이 서버가 제공하는 URL이 아니거나 파일이 없으면 예외
    public byte[] read(String imageUrl) {
        String fileName = imageUrl != null && imageUrl.startsWith(URL_PREFIX) ? imageUrl.substring(URL_PREFIX.length()) : null;
        Path path = find(fileName).orElseThrow(() -> new IllegalStateException("광고 이미지 없음: " + imageUrl));
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("광고 이미지 읽기 실패: " + imageUrl, e);
        }
    }
}